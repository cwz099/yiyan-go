package com.yiyan.go.analysis;

import com.yiyan.go.engine.EngineException;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.MoveResult;
import com.yiyan.go.game.Stone;
import com.yiyan.go.recording.GameArchive;
import com.yiyan.go.recording.GameRecord;
import com.yiyan.go.recording.GameRecorder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

final class KataGoGameAnalyzerTest {
    @TempDir Path directory;

    @Test void composesLossesOpportunitiesAndTrainingFromOutOfOrderEngineResults() throws Exception {
        GameRecord game = sampleGame();
        KataGoAnalysisEngine engine = new KataGoAnalysisEngine(() -> fake("ok"), Duration.ofSeconds(5));

        GameReview review = new KataGoGameAnalyzer(engine).analyze(game);

        assertEquals(24, review.visitsPerPosition());
        assertEquals(2, review.humanMistakes().size());
        assertEquals(1, review.humanMistakes().get(0).moveNumber());
        assertEquals(6, review.humanMistakes().get(0).pointLoss());
        assertEquals("D4", review.humanMistakes().get(0).bestMove());
        assertEquals(2, review.opportunities().size());
        assertEquals(2, review.opportunities().get(0).moveNumber());
        assertEquals(3, review.opportunities().get(0).pointLoss());
        assertTrue(review.headline().contains("第 1 手"));
        assertTrue(review.overview().contains("输赢转折"));
        assertTrue(review.formatted().contains("你赢得或重新获得的机会"));
        assertTrue(review.formatted().contains("下一步怎么练"));
        assertTrue(review.detailForMove(1).contains("该方胜率约下降 30.0%"));
    }

    @Test void rejectsInvalidEngineCoordinatesInsteadOfPublishingBadAdvice() throws Exception {
        GameRecord game = sampleGame();
        KataGoAnalysisEngine engine = new KataGoAnalysisEngine(() -> fake("bad-coordinate"), Duration.ofSeconds(5));
        assertThrows(EngineException.class, () -> new KataGoGameAnalyzer(engine).analyze(game));
    }

    @Test void quickOverviewStaysFactualBeforeEngineAnalysis() throws Exception {
        String overview = QuickGameReview.format(sampleGame());
        assertTrue(overview.contains("你执黑棋，共下 2 手"));
        assertTrue(overview.contains("你认输，白棋获胜"));
        assertTrue(overview.contains("不判断单手好坏"));
        assertTrue(overview.contains("KataGo 分析整盘"));
    }

    private ProcessBuilder fake(String mode) {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", Path.of("target/test-classes").toAbsolutePath().toString(), FakeAnalysis.class.getName(), mode);
    }

    private GameRecord sampleGame() throws Exception {
        String previous = System.getProperty("yiyan.dataDir");
        System.setProperty("yiyan.dataDir", directory.toString());
        try {
            BoardState board = new BoardState(9, 6.5);
            GameRecorder recorder = GameRecorder.start(board, Stone.BLACK, "KataGo · 三段");
            play(recorder, board, 0, 0, 4_000);
            play(recorder, board, 8, 8, 3_000);
            play(recorder, board, 1, 0, 8_000);
            play(recorder, board, 7, 8, 2_000);
            board.resign(Stone.BLACK);
            recorder.finish(board, "你认输，白棋获胜");
            assertEquals("", recorder.error());
            return GameArchive.list().games().get(0);
        } finally {
            if (previous == null) System.clearProperty("yiyan.dataDir");
            else System.setProperty("yiyan.dataDir", previous);
        }
    }

    private static void play(GameRecorder recorder, BoardState board, int x, int y, long elapsed) {
        MoveResult result = board.play(x, y);
        assertTrue(result.success());
        recorder.record(board, result, "测试", "测试说明", elapsed);
        assertEquals("", recorder.error());
    }

    public static final class FakeAnalysis {
        private static final Pattern ID = Pattern.compile("\\\"id\\\":\\\"([^\\\"]+)\\\"");

        public static void main(String[] args) throws Exception {
            String query;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                query = input.readLine();
            }
            Matcher matcher = ID.matcher(query == null ? "" : query);
            if (!matcher.find()) return;
            String id = matcher.group(1);
            if (args[0].equals("bad-coordinate")) {
                System.out.println(response(id, 0, 0, .5,
                        "[{\"move\":\"I9\",\"order\":0,\"scoreLead\":0,\"winrate\":0.5}]"));
                return;
            }
            boolean valid = query.contains("\"rules\":\"chinese\"")
                    && query.contains("\"analyzeTurns\":[0,1,2,3,4]")
                    && query.contains("\"maxVisits\":24")
                    && !query.toLowerCase().contains("api_key");
            if (!valid) {
                System.out.println("{\"id\":\"" + id + "\",\"error\":\"bad request\"}");
                return;
            }
            String[] results = {
                    response(id, 4, -2, .40, moves("C2", -2, .40, "C2", -2, .40)),
                    response(id, 2, -3, .35, moves("C3", 1, .55, "B9", -1, .45)),
                    response(id, 0, 0, .50, moves("D4", 2, .60, "A9", -4, .30)),
                    response(id, 3, -1, .45, moves("G7", -4, .30, "H1", -2, .40)),
                    response(id, 1, -4, .30, moves("E5", -6, .20, "J1", -3, .35))
            };
            for (String result : results) System.out.println(result);
        }

        private static String moves(String best, double bestLead, double bestWin,
                                    String actual, double actualLead, double actualWin) {
            return "[{\"move\":\"" + best + "\",\"order\":0,\"scoreLead\":" + bestLead
                    + ",\"winrate\":" + bestWin + ",\"pv\":[\"" + best + "\",\"E5\"]},"
                    + "{\"move\":\"" + actual + "\",\"order\":1,\"scoreLead\":" + actualLead
                    + ",\"winrate\":" + actualWin + ",\"pv\":[\"" + actual + "\"]}]";
        }

        private static String response(String id, int turn, double lead, double winrate, String moves) {
            return "{\"id\":\"" + id + "\",\"turnNumber\":" + turn
                    + ",\"rootInfo\":{\"scoreLead\":" + lead + ",\"winrate\":" + winrate
                    + "},\"moveInfos\":" + moves + "}";
        }
    }
}
