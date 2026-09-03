package com.yiyan.go.recording;

import com.yiyan.go.diagnostics.AppPaths;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.FinalScore;
import com.yiyan.go.game.ScoreAdjudication;
import com.yiyan.go.game.Move;
import com.yiyan.go.game.MoveResult;
import com.yiyan.go.game.Stone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

/** Records only game data, never API configuration, headers, or credentials. */
public final class GameRecorder {
    private final Path directory;
    private final String id = UUID.randomUUID().toString();
    private final Instant started = Instant.now();
    private Instant updated = started;
    private final BoardState initial;
    private BoardState current;
    private final Stone humanColor;
    private String opponent;
    private final List<GameRecord.Ply> moves = new ArrayList<>();
    private final List<String> events = new ArrayList<>();
    private GameRecord.Status status = GameRecord.Status.IN_PROGRESS;
    private String result = "";
    private FinalScore finalScore;
    private String error = "";
    private byte[] lastSaved;

    private GameRecorder(Path directory, BoardState initial, Stone humanColor, String opponent) {
        this.directory = directory;
        this.initial = initial.copy();
        this.current = initial.copy();
        this.humanColor = humanColor;
        this.opponent = RecordFormat.text(opponent);
        if (initial.moveNumber() != 0 || initial.gameOver() || humanColor == Stone.EMPTY
                || !RecordFormat.state(initial).equals(RecordFormat.state(new BoardState(initial.size(), initial.komi())))) {
            error = "只能从新的空棋盘开始记录";
            return;
        }
        addEvent("GAME_STARTED", "新棋局", initial, null, this.opponent, "", 0);
        persist();
    }

    public static GameRecorder start(BoardState initial, Stone humanColor, String opponent) {
        return start(AppPaths.dataDirectory().resolve("games"), initial, humanColor, opponent);
    }

    static GameRecorder start(Path directory, BoardState initial, Stone humanColor, String opponent) {
        return new GameRecorder(directory, initial, humanColor, opponent);
    }

    public synchronized String id() { return id; }
    public synchronized String error() { return error; }
    public synchronized List<Move> moveHistory() { return moves.stream().map(GameRecord.Ply::move).toList(); }

    public synchronized void opponentChanged(String name, BoardState state) {
        if (!capacity()) return;
        if (!same(current, state)) { error = "切换对手时盘面与记录不一致"; return; }
        opponent = RecordFormat.text(name);
        addEvent("OPPONENT_CHANGED", opponent, state, null, opponent, "", 0);
        persist();
    }

    public synchronized void record(BoardState after, MoveResult played, String source, String motivation, long elapsedMs) {
        if (!capacity() || moves.size() >= RecordFormat.MAX_MOVES) {
            error = "对局记录达到容量上限，请结束本局后开启新局";
            return;
        }
        if (!played.success() || played.move() == null) {
            error = "未接受的落子不能写入对局主线";
            return;
        }
        Move move = played.move();
        BoardState verified = current.copy();
        MoveResult replayed = move.pass() ? verified.pass() : verified.play(move.point().x(), move.point().y());
        if (!replayed.success() || !replayed.move().equals(move) || !same(verified, after)
                || !RecordFormat.captures(replayed.captured()).equals(RecordFormat.captures(played.captured()))) {
            error = "落子与当前记录不一致，未写入不合法记录";
            return;
        }
        elapsedMs = Math.max(0, Math.min(7L * 24 * 60 * 60 * 1000, elapsedMs));
        source = RecordFormat.text(source);
        motivation = RecordFormat.text(motivation);
        moves.add(new GameRecord.Ply(move, played.captured(), source, motivation, elapsedMs, Instant.now(), after));
        current = after.copy();
        status = after.gameOver() ? GameRecord.Status.ESTIMATED : GameRecord.Status.IN_PROGRESS;
        result = after.gameOver() ? "双方连续停一手，等待人工确认死子与结算；尚未判定胜负。" : "";
        finalScore = null;
        addEvent(move.pass() ? "PASS" : "MOVE", "", after, played, source, motivation, elapsedMs);
        persist();
    }

    public synchronized void undo(BoardState state) {
        if (!capacity()) return;
        int count = state.moveNumber();
        if (count < 0 || count > moves.size()) { error = "悔棋手数超出记录范围"; return; }
        BoardState expected = count == 0 ? initial : moves.get(count - 1).position();
        if (!same(expected, state)) { error = "悔棋后的盘面与主线不一致"; return; }
        int removed = moves.size() - count;
        moves.subList(count, moves.size()).clear();
        current = state.copy();
        status = GameRecord.Status.IN_PROGRESS;
        result = "";
        finalScore = null;
        addEvent("UNDO", "退回第 " + count + " 手；移出主线 " + removed + " 手，原始事件保留", state, null, "用户", "", 0);
        persist();
    }

    public synchronized void event(String type, String message, BoardState state) {
        if (!capacity()) return;
        if (state.size() != initial.size() || Double.compare(state.komi(), initial.komi()) != 0) {
            error = "事件盘面设置与本局不一致";
            return;
        }
        String safeType = RecordFormat.text(type);
        addEvent(safeType.isBlank() ? "NOTE" : safeType, RecordFormat.text(message), state, null, "", "", 0);
        persist();
    }

    public synchronized void finish(BoardState state, String resultText) {
        if (!capacity()) return;
        BoardState expected = current.copy();
        if (!expected.gameOver() && RecordFormat.resignation(state) != Stone.EMPTY) expected.resign(state.resignedBy());
        if (!state.gameOver() || !same(expected, state)) { error = "终局盘面与记录不一致"; return; }
        current = state.copy();
        status = RecordFormat.resignation(state) == Stone.EMPTY ? GameRecord.Status.ESTIMATED : GameRecord.Status.RESIGNED;
        result = RecordFormat.text(resultText);
        finalScore = null;
        addEvent("GAME_FINISHED", result, state, null, "", "", 0);
        persist();
    }

    public synchronized void pause(BoardState state) {
        if (status == GameRecord.Status.RESIGNED || status == GameRecord.Status.ESTIMATED || status == GameRecord.Status.SCORED) return;
        if (!capacity()) return;
        if (!same(current, state)) { error = "保存时盘面与记录不一致"; return; }
        status = GameRecord.Status.PAUSED;
        addEvent("GAME_SAVED", "离开棋局；保留未完局记录，不判定胜负", state, null, "", "", 0);
        persist();
    }

    public synchronized void confirmScore(BoardState state, FinalScore score) {
        confirmScore(state, score, "用户确认");
    }

    public synchronized void confirmScore(BoardState state, FinalScore score, String source) {
        if (!capacity()) return;
        if (!state.gameOver() || state.resignedBy() != null || !same(current, state) || score == null) {
            error = "只能确认当前双方停手后的结算";
            return;
        }
        try {
            FinalScore verified = ScoreAdjudication.calculate(state, score.deadStones(), score.neutralPoints());
            if (!verified.equals(score)) { error = "结算明细与盘面不一致"; return; }
            finalScore = verified;
            status = GameRecord.Status.SCORED;
            source = RecordFormat.text(source);
            result = verified.summary() + (source.equals("用户确认") ? "" : " · " + source);
            addEvent("SCORE_CONFIRMED", result + "；死子=" + RecordFormat.captures(new ArrayList<>(score.deadStones()))
                    + "；中立点=" + RecordFormat.captures(new ArrayList<>(score.neutralPoints())), state, null, source, "", 0);
            persist();
        } catch (IllegalArgumentException exception) {
            error = "结算标记不合法，未保存错误结果";
        }
    }

    private boolean capacity() {
        if (events.isEmpty()) { if (error.isEmpty()) error = "记录尚未初始化"; return false; }
        if (events.size() >= RecordFormat.MAX_EVENTS) { error = "对局事件达到容量上限"; return false; }
        return true;
    }

    private static boolean same(BoardState first, BoardState second) {
        return first.size() == second.size() && Double.compare(first.komi(), second.komi()) == 0
                && RecordFormat.state(first).equals(RecordFormat.state(second));
    }

    private void addEvent(String type, String message, BoardState board, MoveResult move,
                          String source, String motivation, long elapsedMs) {
        updated = Instant.now();
        StringBuilder json = new StringBuilder("{\"schemaVersion\":").append(RecordFormat.VERSION)
                .append(",\"gameId\":").append(RecordFormat.json(id))
                .append(",\"sequence\":").append(events.size() + 1)
                .append(",\"time\":").append(RecordFormat.json(updated.toString()))
                .append(",\"type\":").append(RecordFormat.json(type))
                .append(",\"message\":").append(RecordFormat.json(message))
                .append(",\"moveNumber\":").append(board.moveNumber())
                .append(",\"boardSize\":").append(board.size())
                .append(",\"komi\":").append(board.komi())
                .append(",\"board\":").append(RecordFormat.json(RecordFormat.board(board)))
                .append(",\"state\":").append(RecordFormat.json(RecordFormat.state(board)))
                .append(",\"source\":").append(RecordFormat.json(source))
                .append(",\"motivation\":").append(RecordFormat.json(motivation))
                .append(",\"elapsedMs\":").append(elapsedMs);
        if (move != null) {
            json.append(",\"color\":").append(RecordFormat.json(move.move().stone().name()))
                    .append(",\"coordinate\":").append(RecordFormat.json(move.move().pass() ? "PASS" : move.move().coordinate(board.size())))
                    .append(",\"captured\":").append(RecordFormat.json(RecordFormat.captures(move.captured())));
        }
        events.add(json.append('}').toString());
    }

    private Properties properties() {
        Properties p = new Properties();
        p.setProperty("schemaVersion", Integer.toString(RecordFormat.VERSION));
        p.setProperty("id", id);
        p.setProperty("started", started.toString());
        p.setProperty("updated", updated.toString());
        p.setProperty("size", Integer.toString(initial.size()));
        p.setProperty("komi", Double.toString(initial.komi()));
        p.setProperty("humanColor", humanColor.name());
        p.setProperty("opponent", opponent);
        p.setProperty("initial", RecordFormat.state(initial));
        p.setProperty("final", RecordFormat.state(current));
        p.setProperty("resignedBy", RecordFormat.resignation(current).name());
        p.setProperty("status", status.name());
        p.setProperty("result", result);
        if (finalScore != null) {
            p.setProperty("score.dead", RecordFormat.captures(new ArrayList<>(finalScore.deadStones())));
            p.setProperty("score.neutral", RecordFormat.captures(new ArrayList<>(finalScore.neutralPoints())));
            p.setProperty("score.blackTotal", Double.toString(finalScore.blackTotal()));
            p.setProperty("score.whiteTotal", Double.toString(finalScore.whiteTotal()));
        }
        p.setProperty("moves", Integer.toString(moves.size()));
        for (int i = 0; i < moves.size(); i++) {
            GameRecord.Ply ply = moves.get(i);
            Move move = ply.move();
            String prefix = "move." + i + ".";
            p.setProperty(prefix + "stone", move.stone().name());
            p.setProperty(prefix + "action", move.pass() ? "PASS" : "PLAY");
            if (!move.pass()) {
                p.setProperty(prefix + "x", Integer.toString(move.point().x()));
                p.setProperty(prefix + "y", Integer.toString(move.point().y()));
            }
            p.setProperty(prefix + "captures", RecordFormat.captures(ply.captured()));
            p.setProperty(prefix + "source", ply.source());
            p.setProperty(prefix + "motivation", ply.motivation());
            p.setProperty(prefix + "elapsedMs", Long.toString(ply.elapsedMs()));
            p.setProperty(prefix + "time", ply.time().toString());
            p.setProperty(prefix + "state", RecordFormat.state(ply.position()));
        }
        p.setProperty("events", Integer.toString(events.size()));
        for (int i = 0; i < events.size(); i++) p.setProperty("event." + i, events.get(i));
        return p;
    }

    private void persist() {
        try {
            byte[] next = RecordFormat.xml(properties());
            if (lastSaved != null) RecordFormat.atomicWrite(directory.resolve(id + ".xml.bak"), lastSaved);
            RecordFormat.atomicWrite(directory.resolve(id + ".xml"), next);
            lastSaved = next;
            // The XML is authoritative. JSONL is a readable audit mirror, regenerated on every save.
            RecordFormat.atomicWrite(directory.resolve(id + ".events.jsonl"),
                    (String.join("\n", events) + "\n").getBytes(StandardCharsets.UTF_8));
            error = "";
        } catch (IOException | SecurityException e) {
            error = "对局记录保存失败，请检查数据目录权限和磁盘空间";
        }
    }
}
