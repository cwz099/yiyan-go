package com.yiyan.go.recording;

import com.yiyan.go.diagnostics.AppLogs;
import com.yiyan.go.diagnostics.JsonCodec;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.MoveResult;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;
import com.yiyan.go.game.ScoreAdjudication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class GameRecorderTest {
    @TempDir Path directory;

    @Test void movesCapturesPassesAndCompleteSnapshotsSurviveReload() throws Exception {
        BoardState board = new BoardState(9, 6.5);
        GameRecorder recorder = start(board);
        play(recorder, board, 0, 1);
        play(recorder, board, 1, 1);
        play(recorder, board, 1, 0);
        play(recorder, board, 8, 8);
        play(recorder, board, 2, 1);
        play(recorder, board, 8, 7);
        play(recorder, board, 1, 2);
        pass(recorder, board);
        pass(recorder, board);
        recorder.finish(board, "黑棋暂时领先；仅为估算");
        assertEquals("", recorder.error());
        GameRecord game = load(recorder);
        assertEquals(9, game.moves().size());
        assertEquals(6.5, game.komi());
        assertEquals(1, game.finalPosition().blackCaptures());
        assertEquals(List.of(new Point(1, 1)), game.moves().get(6).captured());
        assertTrue(game.moves().get(7).move().pass());
        assertEquals(RecordFormat.state(board), RecordFormat.state(game.finalPosition()));
        assertEquals(GameRecord.Status.ESTIMATED, game.status());
        assertEquals("测试来源", game.moves().get(0).source());
        assertEquals("这手连接棋子", game.moves().get(0).motivation());
        assertEquals(123, game.moves().get(0).elapsedMs());
        assertEquals(0, game.positionAt(0).moveNumber());
        BoardState isolated = game.positionAt(1);
        isolated.pass();
        assertEquals(1, game.positionAt(1).moveNumber());
        for (String event : Files.readAllLines(events(recorder))) assertInstanceOf(Map.class, JsonCodec.parse(event));
    }

    @Test void undoCreatesNewMainLineButPreservesOriginalEvents() throws Exception {
        BoardState board = new BoardState(9);
        GameRecorder recorder = start(board);
        play(recorder, board, 0, 0);
        BoardState retained = board.copy();
        play(recorder, board, 1, 0);
        play(recorder, board, 2, 0);
        recorder.undo(retained);
        play(recorder, retained, 3, 3);
        GameRecord game = load(recorder);
        assertEquals(2, game.moves().size());
        assertEquals(new Point(3, 3), game.moves().get(1).move().point());
        String audit = Files.readString(events(recorder));
        assertTrue(audit.contains("\"type\":\"UNDO\""));
        assertTrue(audit.contains("\"coordinate\":\"B9\""));
        assertTrue(audit.contains("\"coordinate\":\"C9\""));
        assertFalse(GameArchive.toSgf(game).contains(";W[ba]"));
        assertTrue(GameArchive.toSgf(game).contains(";W[dd]"));
    }

    @Test void resignIsPreservedByPauseAndCanBeUndoneAtSameMoveNumber() throws Exception {
        BoardState board = new BoardState(9);
        GameRecorder recorder = start(board);
        play(recorder, board, 0, 0);
        BoardState beforeResignation = board.copy();
        board.resign(Stone.WHITE);
        recorder.finish(board, "你赢了，白棋认输");
        recorder.pause(board);
        GameRecord resigned = load(recorder);
        assertEquals(GameRecord.Status.RESIGNED, resigned.status());
        assertEquals(Stone.WHITE, resigned.finalPosition().resignedBy());
        assertTrue(GameArchive.toSgf(resigned).contains("RE[B+R]"));
        recorder.undo(beforeResignation);
        assertEquals("", recorder.error());
        play(recorder, beforeResignation, 1, 1);
        GameRecord continued = load(recorder);
        assertEquals(GameRecord.Status.IN_PROGRESS, continued.status());
        assertEquals("", continued.result());
        assertNull(continued.finalPosition().resignedBy());
        assertFalse(GameArchive.toSgf(continued).contains("RE["));
    }

    @Test void pauseKeepsUnfinishedGamesSeparateFromEstimatedResults() throws Exception {
        BoardState board = new BoardState(9, 0);
        GameRecorder recorder = start(board);
        recorder.pause(board);
        assertEquals(GameRecord.Status.PAUSED, load(recorder).status());
        pass(recorder, board);
        pass(recorder, board);
        recorder.finish(board, "和棋（基础估算，未确认死活）");
        recorder.pause(board);
        GameRecord game = load(recorder);
        assertEquals(GameRecord.Status.ESTIMATED, game.status());
        String sgf = GameArchive.toSgf(game);
        assertTrue(sgf.contains("RE[?]"));
        assertFalse(sgf.contains("RE[0]"));
        assertTrue(sgf.contains(";B[]"));
        assertTrue(sgf.contains(";W[]"));
        assertTrue(sgf.contains("和棋"));
    }

    @Test void sgfUsesTopLeftCoordinatesEscapedUtf8CommentsAndPlayers() throws Exception {
        BoardState board = new BoardState(13, 7.5);
        GameRecorder recorder = GameRecorder.start(directory, board, Stone.WHITE, "对手]\\AI");
        MoveResult move = board.play(12, 0);
        recorder.record(board, move, "本地", "这里]有\\说明\n下一行", 1);
        GameRecord game = load(recorder);
        String sgf = GameArchive.toSgf(game);
        assertTrue(sgf.contains("SZ[13]KM[7.5]"));
        assertTrue(sgf.contains("PB[对手\\]\\\\AI]PW[你]"));
        assertTrue(sgf.contains(";B[ma]"));
        assertTrue(sgf.contains("这里\\]有\\\\说明\n下一行"));
        Path destination = directory.resolve("中文棋谱.sgf");
        GameArchive.exportSgf(game, destination);
        assertEquals(sgf, Files.readString(destination, StandardCharsets.UTF_8));
    }

    @Test void corruptSnapshotIsQuarantinedAndPreviousAtomicBackupRecovered() throws Exception {
        BoardState board = new BoardState(9);
        GameRecorder recorder = start(board);
        play(recorder, board, 0, 0);
        Files.writeString(snapshot(recorder), "<broken");
        GameArchive.ScanResult scan = GameArchive.list(directory);
        assertEquals(1, scan.games().size());
        assertEquals(0, scan.games().get(0).moves().size());
        assertFalse(scan.warnings().isEmpty());
        assertTrue(scan.warnings().get(0).contains("恢复"));
        assertEquals(0, load(recorder).moves().size());
        try (var quarantined = Files.list(directory.resolve("quarantine"))) {
            assertEquals(1, quarantined.count());
        }
    }

    @Test void semanticCorruptionWithoutBackupIsIsolatedNotReplayed() throws Exception {
        BoardState board = new BoardState(9);
        GameRecorder recorder = start(board);
        play(recorder, board, 0, 0);
        Properties properties = RecordFormat.read(snapshot(recorder));
        properties.setProperty("move.0.stone", "WHITE");
        RecordFormat.atomicWrite(snapshot(recorder), RecordFormat.xml(properties));
        Files.delete(directory.resolve(recorder.id() + ".xml.bak"));
        assertThrows(IOException.class, () -> load(recorder));
        GameArchive.ScanResult scan = GameArchive.list(directory);
        assertTrue(scan.games().isEmpty());
        assertFalse(scan.warnings().isEmpty());
        assertFalse(Files.exists(snapshot(recorder)));
    }

    @Test void loadingRejectsMismatchedSnapshotsBoundsAndInvalidJsonEvents() throws Exception {
        BoardState board = new BoardState(9);
        GameRecorder recorder = start(board);
        play(recorder, board, 0, 0);
        Properties original = RecordFormat.read(snapshot(recorder));
        Properties changed = copy(original);
        changed.setProperty("move.0.state", "not-a-position");
        assertThrows(IOException.class, () -> RecordFormat.decode(changed, recorder.id()));
        Properties badCount = copy(original);
        badCount.setProperty("moves", "5001");
        assertThrows(IOException.class, () -> RecordFormat.decode(badCount, recorder.id()));
        Properties badPoint = copy(original);
        badPoint.setProperty("move.0.x", "9");
        assertThrows(IOException.class, () -> RecordFormat.decode(badPoint, recorder.id()));
        Properties badEvent = copy(original);
        badEvent.setProperty("event.0", "{not json}");
        assertThrows(IOException.class, () -> RecordFormat.decode(badEvent, recorder.id()));
        Properties wrongSequence = copy(original);
        wrongSequence.setProperty("event.0", wrongSequence.getProperty("event.0").replace("\"sequence\":1", "\"sequence\":2"));
        assertThrows(IOException.class, () -> RecordFormat.decode(wrongSequence, recorder.id()));
        Properties badDate = copy(original);
        badDate.setProperty("started", "not-a-timestamp");
        assertThrows(IOException.class, () -> RecordFormat.decode(badDate, recorder.id()));
    }

    @Test void loadingRejectsUnfinishedStatusOnFinishedBoard() throws Exception {
        BoardState board = new BoardState(9);
        GameRecorder recorder = start(board);
        pass(recorder, board);
        pass(recorder, board);
        Properties p = RecordFormat.read(snapshot(recorder));
        p.setProperty("status", "IN_PROGRESS");
        assertThrows(IOException.class, () -> RecordFormat.decode(p, recorder.id()));
        p.setProperty("status", "PAUSED");
        assertThrows(IOException.class, () -> RecordFormat.decode(p, recorder.id()));
    }

    @Test void unicodeFilteringDoesNotSplitSurrogatesAndSavedTextIsRedacted() throws Exception {
        String emoji = "\uD83D\uDE42";
        assertEquals("合法" + emoji, RecordFormat.text("合法\u0001\uD800\uFFFE\uFFFF" + emoji));
        String limited = RecordFormat.text("x".repeat(RecordFormat.MAX_TEXT - 1) + emoji);
        assertEquals(RecordFormat.MAX_TEXT - 1, limited.length());
        String secret = "recording-test-private-key-987654";
        AppLogs.registerSecret(secret);
        BoardState board = new BoardState(9);
        GameRecorder recorder = GameRecorder.start(directory, board, Stone.BLACK, "测试 " + secret);
        recorder.record(board, board.play(0, 0), "来源", "动机\uD800\uFFFF " + emoji + " " + secret, 0);
        assertEquals("", recorder.error());
        GameRecord game = load(recorder);
        assertTrue(game.moves().get(0).motivation().contains(emoji));
        assertFalse(Files.readString(snapshot(recorder)).contains(secret));
        assertFalse(Files.readString(events(recorder)).contains(secret));
        assertFalse(GameArchive.toSgf(game).contains(secret));
    }

    @Test void ioFailureDoesNotCrashAndCanBeInspected() throws Exception {
        Path regularFile = directory.resolve("not-a-directory");
        Files.writeString(regularFile, "occupied");
        BoardState board = new BoardState(9);
        GameRecorder recorder = assertDoesNotThrow(() -> GameRecorder.start(regularFile, board, Stone.BLACK, "本地"));
        assertFalse(recorder.error().isBlank());
        assertDoesNotThrow(() -> recorder.record(board, board.play(0, 0), "本地", "说明", 0));
        assertFalse(recorder.error().isBlank());
    }

    @Test void confirmedScoreSurvivesReloadAndExportsNumericResultAndDeadMarks() throws Exception {
        BoardState board = new BoardState(9, 0);
        GameRecorder recorder = start(board);
        play(recorder, board, 0, 0);
        play(recorder, board, 8, 8);
        BoardState beforePasses = board.copy();
        pass(recorder, board);
        pass(recorder, board);
        var score = ScoreAdjudication.calculate(board, Set.of(new Point(8, 8)), Set.of());
        recorder.confirmScore(board, score);
        recorder.pause(board);
        assertEquals("", recorder.error());
        GameRecord game = load(recorder);
        assertEquals(GameRecord.Status.SCORED, game.status());
        assertEquals(score, game.finalScore());
        assertTrue(GameArchive.toSgf(game).contains("RE[B+81.0]"));
        assertTrue(GameArchive.toSgf(game).contains("MA[ii]"));
        assertTrue(Files.readString(events(recorder)).contains("SCORE_CONFIRMED"));
        recorder.undo(beforePasses);
        assertNull(load(recorder).finalScore());
        assertFalse(GameArchive.toSgf(load(recorder)).contains("RE["));
    }

    @Test void changingProviderUpdatesSavedPlayerWithoutLosingMoveSources() throws Exception {
        BoardState board = new BoardState(9);
        GameRecorder recorder = start(board);
        recorder.opponentChanged("DeepSeek · test-model", board);
        play(recorder, board, 0, 0);
        GameRecord game = load(recorder);
        assertEquals("DeepSeek · test-model", game.opponent());
        assertTrue(GameArchive.toSgf(game).contains("PW[DeepSeek · test-model]"));
        assertEquals("测试来源", game.moves().get(0).source());
    }

    @Test void oldSchemaOneStillLoadsAndTamperedScoreFailsValidation() throws Exception {
        BoardState board = new BoardState(9, 0);
        GameRecorder recorder = start(board);
        play(recorder, board, 0, 0);
        Properties old = RecordFormat.read(snapshot(recorder));
        old.setProperty("schemaVersion", "1");
        for (int i = 0; i < Integer.parseInt(old.getProperty("events")); i++) {
            old.setProperty("event." + i, old.getProperty("event." + i).replace("\"schemaVersion\":2", "\"schemaVersion\":1"));
        }
        assertEquals(1, RecordFormat.decode(old, recorder.id()).moves().size());
        pass(recorder, board);
        pass(recorder, board);
        recorder.confirmScore(board, ScoreAdjudication.calculate(board, Set.of(), Set.of()));
        Properties changed = RecordFormat.read(snapshot(recorder));
        changed.setProperty("score.blackTotal", "999");
        Properties wrongTotal = changed;
        assertThrows(IOException.class, () -> RecordFormat.decode(wrongTotal, recorder.id()));
        changed = RecordFormat.read(snapshot(recorder));
        changed.setProperty("score.dead", "8,8");
        Properties invalidDead = changed;
        assertThrows(IOException.class, () -> RecordFormat.decode(invalidDead, recorder.id()));
    }

    private GameRecorder start(BoardState board) { return GameRecorder.start(directory, board, Stone.BLACK, "本地陪练"); }
    private Path snapshot(GameRecorder recorder) { return directory.resolve(recorder.id() + ".xml"); }
    private Path events(GameRecorder recorder) { return directory.resolve(recorder.id() + ".events.jsonl"); }
    private GameRecord load(GameRecorder recorder) throws IOException { return GameArchive.load(snapshot(recorder)); }
    private static Properties copy(Properties original) { Properties p = new Properties(); p.putAll(original); return p; }
    private static void play(GameRecorder recorder, BoardState board, int x, int y) {
        MoveResult result = board.play(x, y);
        assertTrue(result.success());
        recorder.record(board, result, "测试来源", "这手连接棋子", 123);
        assertEquals("", recorder.error());
    }
    private static void pass(GameRecorder recorder, BoardState board) {
        MoveResult result = board.pass();
        assertTrue(result.success());
        recorder.record(board, result, "测试来源", "停一手", 50);
        assertEquals("", recorder.error());
    }
}
