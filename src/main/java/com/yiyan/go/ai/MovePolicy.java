package com.yiyan.go.ai;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.MoveResult;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Conservative one-ply checks; not a life-and-death solver or a win-rate estimate. */
public final class MovePolicy {
    private MovePolicy() { }
    public record Assessment(Point point, boolean legal, int captured, int libertiesAfter,
                             int connectedGroups, int rescuedGroups, boolean selfAtari,
                             boolean fillsOwnEye, boolean meaningful, String summary) { }
    public record PassAssessment(boolean allowed, String reason, int urgentMoves) { }

    public static Assessment analyze(BoardState position, Point point) {
        return analyze(position, point, new Context(position));
    }

    /** Safe, useful candidates according to shallow checks, not all strategically playable moves. */
    public static List<Assessment> candidates(BoardState position) {
        Context context = new Context(position);
        List<Assessment> result = new ArrayList<>();
        if (position.gameOver()) return List.of();
        for (int y = 0; y < position.size(); y++) {
            for (int x = 0; x < position.size(); x++) {
                if (position.stoneAt(x, y) == Stone.EMPTY) {
                    Assessment a = analyze(position, new Point(x, y), context);
                    if (a.meaningful()) result.add(a);
                }
            }
        }
        return List.copyOf(result);
    }

    public static PassAssessment assessPass(BoardState position) {
        if (position.gameOver()) return new PassAssessment(false, "对局已结束，不能再停一手。", 0);
        List<Assessment> candidates = candidates(position);
        int urgent = (int) candidates.stream().filter(a -> a.captured() > 0 || a.rescuedGroups() > 0).count();
        if (candidates.isEmpty()) {
            return new PassAssessment(true, "暂不落子，等待对方应手。", 0);
        }
        int stones = 0;
        for (int y = 0; y < position.size(); y++) {
            for (int x = 0; x < position.size(); x++) {
                if (position.stoneAt(x, y) != Stone.EMPTY) stones++;
            }
        }
        boolean beyondOpening = stones >= position.size() || position.moveNumber() >= position.size() * 2;
        if (position.consecutivePasses() == 1 && beyondOpening && urgent == 0) {
            return new PassAssessment(true, "同意停手，进入结算。", 0);
        }
        return new PassAssessment(false, urgent > 0
                ? "仍有 " + urgent + " 个可直接提子或救一口气棋块的候选点，请先处理局部任务。"
                : "仍有安全且有作用的候选落点；当前未满足双方收束条件，请继续落子。", urgent);
    }

    /** Discards unverified model prose deliberately; preserves the model-selected coordinate only. */
    public static String explainModelChoice(BoardState position, Point point, String rawMotivation) {
        return explainModelChoice(position, point, "", rawMotivation);
    }

    /** Only model-selected intent labels are shown; tactical labels require observable support. */
    public static String explainModelChoice(BoardState position, Point point, String intent, String rawMotivation) {
        String coordinate = point == null || point.x() < 0 || point.y() < 0
                || point.x() >= position.size() || point.y() >= position.size()
                ? "无效坐标" : point.coordinate(position.size());
        Assessment assessment = analyze(position, point);
        if (assessment.captured() > 0) return "落在 " + coordinate + "，提掉 " + assessment.captured() + " 子。";
        if (assessment.rescuedGroups() > 0) return "补在 " + coordinate + "，为受威胁的棋块增加气。现在有 " + assessment.libertiesAfter() + " 口气。";
        if (assessment.connectedGroups() >= 2) return "落在 " + coordinate + "，连接 " + assessment.connectedGroups() + " 块棋。";
        String intention = switch (intent == null ? "" : intent.toLowerCase(java.util.Locale.ROOT)) {
            case "develop" -> "，尝试向外发展";
            case "probe" -> "，试探对方的应手";
            default -> "";
        };
        return "落在 " + coordinate + intention + "。这块棋有 " + assessment.libertiesAfter() + " 口气。";
    }

    private static Assessment analyze(BoardState board, Point point, Context context) {
        if (point == null || point.x() < 0 || point.y() < 0 || point.x() >= board.size() || point.y() >= board.size()) {
            return rejected(point, "坐标不在棋盘内。");
        }
        BoardState after = board.copy();
        MoveResult result = after.play(point.x(), point.y());
        if (!result.success()) return rejected(point, result.message());
        Stone color = board.turn();
        Set<Integer> ownGroups = new HashSet<>();
        for (Point n : neighbors(board, point)) {
            if (board.stoneAt(n.x(), n.y()) == color) ownGroups.add(context.groupIds[n.x()][n.y()]);
        }
        int liberties = after.libertiesAt(point.x(), point.y());
        int rescued = 0;
        if (liberties > 1) {
            for (int id : ownGroups) if (context.groupLiberties.get(id) == 1) rescued++;
        }
        int captured = result.captured().size();
        boolean eye = isOwnEye(board, point);
        boolean selfAtari = liberties == 1;
        Region region = context.regions[point.x()][point.y()];
        boolean quietOwnTerritory = region != null && region.border().equals(Set.of(color))
                && region.size() <= board.size() * board.size() / 2 && ownGroups.size() <= 1;
        boolean tinyEnemyTerritory = region != null && region.border().equals(Set.of(color.opposite()))
                && region.size() <= 3;
        boolean meaningful = captured > 0 || rescued > 0
                || (!eye && !selfAtari && !quietOwnTerritory && !tinyEnemyTerritory);
        if (eye && captured == 0 && rescued == 0) meaningful = false;
        StringBuilder facts = new StringBuilder("落子合法");
        if (captured > 0) facts.append("，实际提掉 ").append(captured).append(" 子");
        facts.append("，落子后所属棋块有 ").append(liberties).append(" 口气");
        if (ownGroups.size() >= 2) facts.append("，连接了 ").append(ownGroups.size()).append(" 块原本分开的己方棋");
        if (rescued > 0) facts.append("，让 ").append(rescued).append(" 块原先仅一口气的己方棋脱离一口气状态");
        if (eye) facts.append("；此处符合己方单点真眼形状，通常不应自行填入");
        if (selfAtari) facts.append("；落子后仅一口气，有被提风险");
        if (quietOwnTerritory && captured == 0 && rescued == 0) facts.append("；这里只是在已有己方围空内填子，未发现直接收益");
        if (tinyEnemyTerritory && captured == 0) facts.append("；这是对方围住的小空，未发现直接提子收益");
        facts.append("。");
        return new Assessment(point, true, captured, liberties, ownGroups.size(), rescued,
                selfAtari, eye, meaningful, facts.toString());
    }

    private static Assessment rejected(Point point, String reason) {
        return new Assessment(point, false, 0, 0, 0, 0, false, false, false, reason);
    }

    private static boolean isOwnEye(BoardState board, Point point) {
        Stone color = board.turn();
        for (Point n : neighbors(board, point)) {
            if (board.stoneAt(n.x(), n.y()) != color) return false;
        }
        int badDiagonals = 0, offBoard = 0;
        for (int dx : new int[]{-1, 1}) {
            for (int dy : new int[]{-1, 1}) {
                int x = point.x() + dx, y = point.y() + dy;
                if (x < 0 || y < 0 || x >= board.size() || y >= board.size()) offBoard++;
                else if (board.stoneAt(x, y) != color) badDiagonals++;
            }
        }
        return offBoard > 0 ? badDiagonals == 0 : badDiagonals <= 1;
    }

    private static List<Point> neighbors(BoardState board, Point point) {
        List<Point> neighbors = new ArrayList<>(4);
        if (point.x() > 0) neighbors.add(new Point(point.x() - 1, point.y()));
        if (point.y() > 0) neighbors.add(new Point(point.x(), point.y() - 1));
        if (point.x() + 1 < board.size()) neighbors.add(new Point(point.x() + 1, point.y()));
        if (point.y() + 1 < board.size()) neighbors.add(new Point(point.x(), point.y() + 1));
        return neighbors;
    }

    private record Region(int size, Set<Stone> border) { }
    private static final class Context {
        private final int[][] groupIds;
        private final List<Integer> groupLiberties = new ArrayList<>();
        private final Region[][] regions;
        Context(BoardState board) {
            int size = board.size();
            groupIds = new int[size][size];
            regions = new Region[size][size];
            boolean[][] visited = new boolean[size][size];
            for (int y = 0; y < size; y++) {
                for (int x = 0; x < size; x++) {
                    if (visited[x][y]) continue;
                    Stone color = board.stoneAt(x, y);
                    Set<Point> group = new HashSet<>(), liberties = new HashSet<>();
                    Set<Stone> border = new HashSet<>();
                    ArrayDeque<Point> queue = new ArrayDeque<>();
                    queue.add(new Point(x, y));
                    visited[x][y] = true;
                    while (!queue.isEmpty()) {
                        Point current = queue.remove();
                        group.add(current);
                        for (Point n : neighbors(board, current)) {
                            Stone other = board.stoneAt(n.x(), n.y());
                            if (other == color && !visited[n.x()][n.y()]) {
                                visited[n.x()][n.y()] = true;
                                queue.add(n);
                            } else if (other != color) {
                                if (other == Stone.EMPTY) liberties.add(n);
                                else border.add(other);
                            }
                        }
                    }
                    if (color == Stone.EMPTY) {
                        Region region = new Region(group.size(), Set.copyOf(border));
                        for (Point p : group) regions[p.x()][p.y()] = region;
                    } else {
                        int id = groupLiberties.size();
                        groupLiberties.add(liberties.size());
                        for (Point p : group) groupIds[p.x()][p.y()] = id;
                    }
                }
            }
        }
    }
}
