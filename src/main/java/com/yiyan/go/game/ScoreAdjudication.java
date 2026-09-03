package com.yiyan.go.game;

import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;
import java.util.function.Predicate;

/** Counts a copy of the board; it never guesses whether a living group should be dead. */
public final class ScoreAdjudication {
    private ScoreAdjudication() {}

    /**
     * Dead marks must contain whole orthogonally connected groups. Neutral marks are seeds:
     * each seed makes its complete empty region neutral after dead stones are removed.
     * Captured stones are not added separately under Chinese area scoring.
     */
    public static FinalScore calculate(BoardState original, Set<Point> dead, Set<Point> neutral) {
        BoardState board = Objects.requireNonNull(original, "棋盘不能为空").copy();
        Set<Point> deadCopy = checkedDead(board, dead);
        Set<Point> neutralSeeds = Set.copyOf(Objects.requireNonNull(neutral, "中立标记不能为空"));
        for (Point point : neutralSeeds) {
            checkInside(board, point);
            if (at(board, deadCopy, point) != Stone.EMPTY) {
                throw new IllegalArgumentException("中立标记只能放在移除死子后的空点");
            }
        }
        int blackStones = 0, whiteStones = 0, blackTerritory = 0, whiteTerritory = 0, neutralCount = 0;
        Set<Point> visited = new HashSet<>();
        Set<Point> normalizedNeutral = new HashSet<>();
        for (int y = 0; y < board.size(); y++) {
            for (int x = 0; x < board.size(); x++) {
                Point point = new Point(x, y);
                Stone color = at(board, deadCopy, point);
                if (color == Stone.BLACK) blackStones++;
                else if (color == Stone.WHITE) whiteStones++;
                else if (!visited.contains(point)) {
                    Set<Point> region = connected(board.size(), point, p -> at(board, deadCopy, p) == Stone.EMPTY);
                    visited.addAll(region);
                    boolean forcedNeutral = region.stream().anyMatch(neutralSeeds::contains);
                    Set<Stone> borders = new HashSet<>();
                    for (Point empty : region) {
                        for (Point neighbor : neighbors(board.size(), empty)) {
                            Stone border = at(board, deadCopy, neighbor);
                            if (border != Stone.EMPTY) borders.add(border);
                        }
                    }
                    if (forcedNeutral) normalizedNeutral.addAll(region);
                    if (forcedNeutral || borders.size() != 1) neutralCount += region.size();
                    else if (borders.contains(Stone.BLACK)) blackTerritory += region.size();
                    else whiteTerritory += region.size();
                }
            }
        }
        return new FinalScore(board.size(), deadCopy, normalizedNeutral, blackStones, whiteStones,
                blackTerritory, whiteTerritory, neutralCount, board.komi());
    }

    public static Set<Point> groupAt(BoardState board, Point point) {
        Objects.requireNonNull(board, "棋盘不能为空");
        checkInside(board, point);
        Stone color = board.stoneAt(point.x(), point.y());
        if (color == Stone.EMPTY) return Set.of();
        return Set.copyOf(connected(board.size(), point, p -> board.stoneAt(p.x(), p.y()) == color));
    }

    public static Set<Point> emptyRegionAt(BoardState board, Set<Point> dead, Point point) {
        Objects.requireNonNull(board, "棋盘不能为空");
        Set<Point> deadCopy = checkedDead(board, dead);
        checkInside(board, point);
        if (at(board, deadCopy, point) != Stone.EMPTY) return Set.of();
        return Set.copyOf(connected(board.size(), point, p -> at(board, deadCopy, p) == Stone.EMPTY));
    }

    private static Set<Point> checkedDead(BoardState board, Set<Point> dead) {
        Set<Point> result = Set.copyOf(Objects.requireNonNull(dead, "死子标记不能为空"));
        Set<Point> visited = new HashSet<>();
        for (Point point : result) {
            checkInside(board, point);
            if (board.stoneAt(point.x(), point.y()) == Stone.EMPTY) {
                throw new IllegalArgumentException("空点不能标记为死子");
            }
            if (visited.contains(point)) continue;
            Set<Point> group = groupAt(board, point);
            if (!result.containsAll(group)) throw new IllegalArgumentException("请标记整块棋，不能只移除棋块的一部分");
            visited.addAll(group);
        }
        return result;
    }

    private static Stone at(BoardState board, Set<Point> dead, Point point) {
        return dead.contains(point) ? Stone.EMPTY : board.stoneAt(point.x(), point.y());
    }

    private static void checkInside(BoardState board, Point point) {
        if (point == null || point.x() < 0 || point.y() < 0
                || point.x() >= board.size() || point.y() >= board.size()) {
            throw new IllegalArgumentException("结算标记超出棋盘");
        }
    }

    private static Set<Point> connected(int size, Point start, Predicate<Point> included) {
        Set<Point> found = new HashSet<>();
        ArrayDeque<Point> queue = new ArrayDeque<>();
        found.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            for (Point neighbor : neighbors(size, queue.removeFirst())) {
                if (included.test(neighbor) && found.add(neighbor)) queue.addLast(neighbor);
            }
        }
        return found;
    }

    private static Set<Point> neighbors(int size, Point p) {
        Set<Point> points = new HashSet<>(4);
        if (p.x() > 0) points.add(new Point(p.x() - 1, p.y()));
        if (p.y() > 0) points.add(new Point(p.x(), p.y() - 1));
        if (p.x() + 1 < size) points.add(new Point(p.x() + 1, p.y()));
        if (p.y() + 1 < size) points.add(new Point(p.x(), p.y() + 1));
        return points;
    }
}
