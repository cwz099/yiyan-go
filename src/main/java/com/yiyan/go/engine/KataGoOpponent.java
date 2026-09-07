package com.yiyan.go.engine;

import com.yiyan.go.ai.AiDecision;
import com.yiyan.go.ai.GoOpponent;
import com.yiyan.go.ai.MovePolicy;
import com.yiyan.go.diagnostics.AppLogs;
import com.yiyan.go.game.*;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** One lazily loaded engine per game; the recorded history is authoritative after undo. */
public final class KataGoOpponent implements GoOpponent {
    @FunctionalInterface interface ProcessFactory { ProcessBuilder create() throws IOException; }
    private final GoDifficulty difficulty;
    private final ProcessFactory factory;
    private final String gameId;
    private volatile GtpSession session;
    private volatile boolean closed;
    private String engineSessionId = "";
    private List<Move> synchronizedHistory = List.of();
    private int size;
    private double komi;

    public KataGoOpponent(GoDifficulty difficulty) {
        this(difficulty, () -> process(KataGoConfig.discover(), difficulty), "");
    }

    public KataGoOpponent(GoDifficulty difficulty, String gameId) {
        this(difficulty, () -> process(KataGoConfig.discover(), difficulty), gameId);
    }

    public KataGoOpponent(KataGoConfig config, GoDifficulty difficulty) {
        this(difficulty, () -> process(config, difficulty), "");
    }

    KataGoOpponent(GoDifficulty difficulty, ProcessFactory factory) {
        this(difficulty, factory, "");
    }

    KataGoOpponent(GoDifficulty difficulty, ProcessFactory factory, String gameId) {
        this.difficulty = Objects.requireNonNull(difficulty);
        this.factory = Objects.requireNonNull(factory);
        this.gameId = gameId == null ? "" : gameId;
    }

    private static ProcessBuilder process(KataGoConfig config, GoDifficulty level) throws IOException {
        config.validate();
        ProcessBuilder builder = new ProcessBuilder(config.executable().toString(), "gtp",
                "-model", config.model().toString(), "-config", config.config().toString(),
                "-override-config", overrides(level));
        return builder.directory(config.executable().getParent().toFile());
    }

    static String overrides(GoDifficulty level) {
        return "maxVisits=" + level.visits() + ",maxTime=" + level.seconds()
                + ",chosenMoveTemperature=" + level.temperature()
                + ",chosenMoveTemperatureEarly=" + level.temperature()
                + ",allowResignation=false,resignThreshold=-0.90,resignConsecTurns=3"
                + ",ponderingEnabled=false";
    }

    @Override public AiDecision chooseMove(BoardState position) throws Exception {
        if (position.moveNumber() != 0) throw new EngineException("KataGo 需要完整棋谱才能同步盘面");
        return chooseMove(position, List.of());
    }

    @Override public synchronized AiDecision chooseMove(BoardState position, List<Move> history) throws Exception {
        BoardState board = position.copy();
        List<Move> moves = List.copyOf(history);
        long started = System.nanoTime();
        try {
            validateHistory(board, moves);
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            GtpSession active = session;
            if (active == null) {
                engineSessionId = UUID.randomUUID().toString();
                try {
                    active = new GtpSession(factory.create(), Duration.ofSeconds(60));
                } catch (IOException exception) {
                    throw new EngineException("KataGo 无法启动，请检查引擎和模型文件", exception);
                }
                session = active;
                if (closed) throw new InterruptedException();
                if (!active.command("name").equalsIgnoreCase("KataGo")) throw new EngineException("引擎身份不匹配");
                size = 0;
                AppLogs.event("engine", "ranked_engine_started", diagnosticFields(board, moves));
            }
            active.resetBudget(Duration.ofSeconds(60));
            String syncMode = synchronize(active, board, moves);
            Map<String, Object> startedFields = diagnosticFields(board, moves);
            startedFields.put("syncMode", syncMode);
            AppLogs.event("engine", "ranked_move_started", startedFields);
            String coordinate = active.command("genmove " + color(board.turn())).trim();
            AiDecision decision = parseDecision(board, coordinate);
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            MoveResult result = decision.pass() ? board.pass() : board.play(decision.point().x(), decision.point().y());
            List<Move> next = new ArrayList<>(moves);
            next.add(result.move());
            synchronizedHistory = List.copyOf(next);
            Map<String, Object> completed = new LinkedHashMap<>(startedFields);
            completed.put("syncMode", syncMode);
            completed.put("action", decision.pass() ? "pass" : "play");
            completed.put("coordinate", decision.pass() ? "PASS" : decision.point().coordinate(board.size()));
            completed.put("historyMoves", moves.size() + 1);
            completed.put("durationMs", elapsedMs(started));
            AppLogs.event("engine", "ranked_move_completed", completed);
            return decision;
        } catch (Exception exception) {
            Map<String, Object> failed = diagnosticFields(board, moves);
            failed.put("durationMs", elapsedMs(started));
            failed.put("errorCode", AppLogs.errorCode(exception));
            failed.put("errorClass", AppLogs.unwrap(exception).getClass().getSimpleName());
            failed.put("reason", AppLogs.safeError(exception));
            AppLogs.event("engine", "ranked_move_failed", failed);
            // A failed/cancelled genmove may already have changed the engine. Recreate before retrying.
            discardSession("failed");
            throw exception;
        }
    }

    private String synchronize(GtpSession active, BoardState board, List<Move> history) throws Exception {
        boolean prefix = size == board.size() && Double.compare(komi, board.komi()) == 0
                && history.size() >= synchronizedHistory.size()
                && history.subList(0, synchronizedHistory.size()).equals(synchronizedHistory);
        int previousMoves = synchronizedHistory.size();
        if (!prefix) {
            active.command("boardsize " + board.size());
            active.command("clear_board");
            active.command("kata-set-rules chinese");
            active.command("komi " + board.komi());
            size = board.size();
            komi = board.komi();
            synchronizedHistory = List.of();
        }
        for (int i = synchronizedHistory.size(); i < history.size(); i++) {
            Move move = history.get(i);
            active.command("play " + color(move.stone()) + " " + (move.pass() ? "pass" : move.coordinate(board.size())));
        }
        synchronizedHistory = history;
        if (!prefix) return "reset";
        return history.size() == previousMoves ? "current" : "incremental";
    }

    static void validateHistory(BoardState board, List<Move> history) throws IOException {
        if (board.gameOver()) throw new EngineException("对局已经结束");
        BoardState replay = new BoardState(board.size(), board.komi());
        for (Move move : history) {
            MoveResult result = move.pass() ? replay.pass() : replay.play(move.point().x(), move.point().y());
            if (!result.success() || !result.move().equals(move)) throw new EngineException("棋谱不合法，无法同步 KataGo");
        }
        if (replay.moveNumber() != board.moveNumber() || replay.turn() != board.turn()
                || replay.consecutivePasses() != board.consecutivePasses()
                || replay.blackCaptures() != board.blackCaptures() || replay.whiteCaptures() != board.whiteCaptures()
                || !Objects.equals(replay.lastMove(), board.lastMove())
                || !replay.asciiDiagram().equals(board.asciiDiagram())) {
            throw new EngineException("棋谱与当前盘面不一致");
        }
    }

    static AiDecision parseDecision(BoardState board, String text) throws IOException {
        if (text.equalsIgnoreCase("pass")) return AiDecision.pass("暂不落子，等待你的应手；双方停手后再核对终局。");
        if (!text.matches("[A-HJ-T](?:[1-9]|1[0-9])")) throw new EngineException("KataGo 返回了无效落点");
        Point point = new Point("ABCDEFGHJKLMNOPQRST".indexOf(text.charAt(0)),
                board.size() - Integer.parseInt(text.substring(1)));
        BoardState after = board.copy();
        if (!after.play(point.x(), point.y()).success()) throw new EngineException("KataGo 落点与当前盘面不符");
        return AiDecision.play(point, MovePolicy.explainModelChoice(board, point, ""));
    }

    private static String color(Stone stone) { return stone == Stone.BLACK ? "B" : "W"; }
    @Override public String displayName() { return difficulty.opponentName(); }

    private Map<String, Object> diagnosticFields(BoardState board, List<Move> history) {
        Map<String, Object> fields = new LinkedHashMap<>();
        if (!gameId.isBlank()) fields.put("gameId", gameId);
        if (!engineSessionId.isBlank()) fields.put("engineSessionId", engineSessionId);
        fields.put("engine", "KataGo");
        fields.put("difficulty", difficulty.toString());
        fields.put("maxVisits", difficulty.visits());
        fields.put("maxTimeMs", Math.round(difficulty.seconds() * 1000));
        fields.put("temperature", difficulty.temperature());
        fields.put("boardSize", board.size());
        fields.put("komi", board.komi());
        fields.put("moveNumber", board.moveNumber() + 1);
        fields.put("playerColor", board.turn().name());
        fields.put("historyMoves", history.size());
        return fields;
    }

    private static long elapsedMs(long started) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);
    }

    private void discardSession(String status) {
        GtpSession active = session;
        session = null;
        if (active != null) {
            active.close();
            Map<String, Object> fields = new LinkedHashMap<>();
            if (!gameId.isBlank()) fields.put("gameId", gameId);
            if (!engineSessionId.isBlank()) fields.put("engineSessionId", engineSessionId);
            fields.put("engine", "KataGo");
            fields.put("difficulty", difficulty.toString());
            fields.put("historyMoves", synchronizedHistory.size());
            fields.put("status", status);
            AppLogs.event("engine", "ranked_engine_stopped", fields);
        }
        engineSessionId = "";
    }

    @Override public void close() {
        closed = true;
        discardSession("closed");
    }
}
