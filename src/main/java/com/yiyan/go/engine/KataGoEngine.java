package com.yiyan.go.engine;

import com.yiyan.go.game.*;
import java.io.IOException;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;

public final class KataGoEngine implements ScoreEngine {
    private static final Pattern SCORE = Pattern.compile("[BW]\\+(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private final KataGoConfig config;

    public KataGoEngine(KataGoConfig config) { this.config = Objects.requireNonNull(config); }

    @Override public EngineVerdict evaluate(BoardState position, List<Move> history) throws Exception {
        config.validate();
        BoardState board = position.copy();
        history = List.copyOf(history);
        validateHistory(board, history);
        long started = System.nanoTime();
        ProcessBuilder builder = new ProcessBuilder(config.executable().toString(), "gtp",
                "-model", config.model().toString(), "-config", config.config().toString());
        builder.directory(config.executable().getParent().toFile());
        try (GtpSession session = new GtpSession(builder, Duration.ofSeconds(120))) {
            if (!session.command("name").equalsIgnoreCase("KataGo")) throw new IOException("引擎身份不匹配");
            String version = session.command("version");
            if (version.length() > 80) throw new IOException("引擎版本格式无效");
            session.command("boardsize " + board.size());
            session.command("clear_board");
            session.command("kata-set-rules chinese");
            session.command("komi " + board.komi());
            for (Move move : history) {
                session.command("play " + (move.stone() == Stone.BLACK ? "B" : "W") + " "
                        + (move.pass() ? "pass" : move.coordinate(board.size())));
            }
            String rawDead = session.command("final_status_list dead");
            String rawScore = session.command("final_score");
            Set<Point> dead = parseDead(board, rawDead);
            FinalScore score = ScoreAdjudication.calculate(board, dead, Set.of());
            double whiteLead = parseWhiteLead(rawScore, board.size(), board.komi());
            // A lead estimate is not a settled count. Only exact agreement commits a result automatically.
            boolean consistent = Math.abs(score.whiteTotal() - score.blackTotal() - whiteLead) < 0.001
                    && score.blackStones() + score.whiteStones() > 0;
            return new EngineVerdict("KataGo " + version, config.model().getFileName().toString(), rawScore,
                    score, (System.nanoTime() - started) / 1_000_000, consistent);
        }
    }

    static Set<Point> parseDead(BoardState board, String text) throws IOException {
        Set<Point> dead = new HashSet<>();
        if (text.isBlank()) return Set.of();
        for (String coordinate : text.trim().split("\\s+")) {
            if (!coordinate.matches("[A-HJ-T](?:[1-9]|1[0-9])")) throw new IOException("KataGo 死子坐标格式无效");
            int x = "ABCDEFGHJKLMNOPQRST".indexOf(coordinate.charAt(0));
            int y = board.size() - Integer.parseInt(coordinate.substring(1));
            if (x < 0 || x >= board.size() || y < 0 || y >= board.size()
                    || board.stoneAt(x, y) == Stone.EMPTY) throw new IOException("KataGo 死子坐标与盘面不符");
            dead.add(new Point(x, y));
        }
        // Reject partial groups rather than silently expanding an inconsistent engine response.
        try { ScoreAdjudication.calculate(board, dead, Set.of()); }
        catch (IllegalArgumentException e) { throw new IOException("KataGo 死子分组不完整", e); }
        return Set.copyOf(dead);
    }

    static double parseWhiteLead(String text, int size, double komi) throws IOException {
        if (text.equals("0")) return 0;
        if (!SCORE.matcher(text).matches()) throw new IOException("KataGo 分数格式无效");
        double margin = Double.parseDouble(text.substring(2));
        if (!Double.isFinite(margin) || margin > size * size + komi) throw new IOException("KataGo 分数超出范围");
        return text.charAt(0) == 'W' ? margin : -margin;
    }

    private static void validateHistory(BoardState board, List<Move> history) throws IOException {
        if (!board.gameOver() || board.resignedBy() != null || board.consecutivePasses() != 2) {
            throw new IOException("双方停手后才能进行终局判定");
        }
        BoardState replay = new BoardState(board.size(), board.komi());
        for (Move move : history) {
            MoveResult result = move.pass() ? replay.pass() : replay.play(move.point().x(), move.point().y());
            if (!result.success() || !result.move().equals(move)) throw new IOException("送审棋谱不合法");
        }
        if (replay.moveNumber() != board.moveNumber() || replay.turn() != board.turn()
                || !replay.gameOver() || !replay.asciiDiagram().equals(board.asciiDiagram())) {
            throw new IOException("送审棋谱与当前盘面不一致");
        }
    }
}
