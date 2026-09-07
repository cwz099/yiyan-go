package com.yiyan.go.engine;

import com.yiyan.go.ai.AiDecision;
import com.yiyan.go.ai.GoOpponent;
import com.yiyan.go.ai.MovePolicy;
import com.yiyan.go.game.*;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** One lazily loaded engine per game; the recorded history is authoritative after undo. */
public final class KataGoOpponent implements GoOpponent {
    @FunctionalInterface interface ProcessFactory { ProcessBuilder create() throws IOException; }
    private final GoDifficulty difficulty;
    private final ProcessFactory factory;
    private volatile GtpSession session;
    private volatile boolean closed;
    private List<Move> synchronizedHistory = List.of();
    private int size;
    private double komi;

    public KataGoOpponent(GoDifficulty difficulty) {
        this(difficulty, () -> process(KataGoConfig.discover(), difficulty));
    }

    public KataGoOpponent(KataGoConfig config, GoDifficulty difficulty) {
        this(difficulty, () -> process(config, difficulty));
    }

    KataGoOpponent(GoDifficulty difficulty, ProcessFactory factory) {
        this.difficulty = Objects.requireNonNull(difficulty);
        this.factory = Objects.requireNonNull(factory);
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
        validateHistory(board, moves);
        if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
        try {
            GtpSession active = session;
            if (active == null) {
                active = new GtpSession(factory.create(), Duration.ofSeconds(60));
                session = active;
                if (closed) throw new InterruptedException();
                if (!active.command("name").equalsIgnoreCase("KataGo")) throw new EngineException("引擎身份不匹配");
                size = 0;
            }
            active.resetBudget(Duration.ofSeconds(60));
            synchronize(active, board, moves);
            String coordinate = active.command("genmove " + color(board.turn())).trim();
            AiDecision decision = parseDecision(board, coordinate);
            if (closed || Thread.currentThread().isInterrupted()) throw new InterruptedException();
            MoveResult result = decision.pass() ? board.pass() : board.play(decision.point().x(), decision.point().y());
            List<Move> next = new ArrayList<>(moves);
            next.add(result.move());
            synchronizedHistory = List.copyOf(next);
            return decision;
        } catch (Exception exception) {
            // A failed/cancelled genmove may already have changed the engine. Recreate before retrying.
            discardSession();
            throw exception;
        }
    }

    private void synchronize(GtpSession active, BoardState board, List<Move> history) throws Exception {
        boolean prefix = size == board.size() && Double.compare(komi, board.komi()) == 0
                && history.size() >= synchronizedHistory.size()
                && history.subList(0, synchronizedHistory.size()).equals(synchronizedHistory);
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

    private void discardSession() {
        GtpSession active = session;
        session = null;
        if (active != null) active.close();
    }

    @Override public void close() {
        closed = true;
        discardSession();
    }
}
