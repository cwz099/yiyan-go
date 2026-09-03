package com.yiyan.go.game;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Queue;
import java.util.Set;

public final class BoardState {
    public static final int DEFAULT_SIZE = 19;
    public static final double DEFAULT_KOMI = 7.5;

    private final int size;
    private final double komi;
    private Stone[][] board;
    private Stone turn;
    private int moveNumber;
    private int consecutivePasses;
    private int blackCaptures;
    private int whiteCaptures;
    private String positionBeforeLastMove;
    private Move lastMove;
    private boolean gameOver;
    private Stone resignedBy;

    public BoardState() {
        this(DEFAULT_SIZE);
    }

    public BoardState(int size) {
        this(size, DEFAULT_KOMI);
    }

    public BoardState(int size, double komi) {
        if (size < 5 || size > 19) {
            throw new IllegalArgumentException("棋盘尺寸必须在 5 到 19 之间");
        }
        if (!Double.isFinite(komi) || komi < 0 || komi > 30) {
            throw new IllegalArgumentException("贴目必须在 0 到 30 之间");
        }
        this.size = size;
        this.komi = komi;
        this.board = emptyBoard(size);
        this.turn = Stone.BLACK;
    }

    private BoardState(BoardState source) {
        this.size = source.size;
        this.komi = source.komi;
        this.board = copyBoard(source.board);
        this.turn = source.turn;
        this.moveNumber = source.moveNumber;
        this.consecutivePasses = source.consecutivePasses;
        this.blackCaptures = source.blackCaptures;
        this.whiteCaptures = source.whiteCaptures;
        this.positionBeforeLastMove = source.positionBeforeLastMove;
        this.lastMove = source.lastMove;
        this.gameOver = source.gameOver;
        this.resignedBy = source.resignedBy;
    }

    public BoardState copy() {
        return new BoardState(this);
    }

    public MoveResult play(int x, int y) {
        if (gameOver) {
            return MoveResult.rejected("棋局已经结束");
        }
        if (!isInside(x, y)) {
            return MoveResult.rejected("这里不在棋盘上");
        }
        if (board[x][y] != Stone.EMPTY) {
            return MoveResult.rejected("这里已经有棋子了");
        }

        Stone[][] next = copyBoard(board);
        String before = encode(board);
        next[x][y] = turn;
        List<Point> captured = new ArrayList<>();

        for (Point neighbor : neighbors(x, y)) {
            if (next[neighbor.x()][neighbor.y()] != turn.opposite()) {
                continue;
            }
            Set<Point> group = groupAt(next, neighbor.x(), neighbor.y());
            if (liberties(next, group).isEmpty()) {
                captured.addAll(group);
                for (Point point : group) {
                    next[point.x()][point.y()] = Stone.EMPTY;
                }
            }
        }

        Set<Point> ownGroup = groupAt(next, x, y);
        if (liberties(next, ownGroup).isEmpty()) {
            return MoveResult.rejected("这手棋没有气，不能落在这里");
        }

        String after = encode(next);
        if (positionBeforeLastMove != null && positionBeforeLastMove.equals(after)) {
            return MoveResult.rejected("这会立即还原上一局面，需要先在别处应一手");
        }

        Stone playedStone = turn;
        board = next;
        moveNumber++;
        lastMove = Move.play(playedStone, new Point(x, y), moveNumber);
        if (playedStone == Stone.BLACK) {
            blackCaptures += captured.size();
        } else {
            whiteCaptures += captured.size();
        }
        consecutivePasses = 0;
        positionBeforeLastMove = before;
        turn = turn.opposite();
        return MoveResult.accepted(lastMove, captured);
    }

    public MoveResult pass() {
        if (gameOver) {
            return MoveResult.rejected("棋局已经结束");
        }
        String before = encode(board);
        Stone playedStone = turn;
        moveNumber++;
        lastMove = Move.pass(playedStone, moveNumber);
        consecutivePasses++;
        positionBeforeLastMove = before;
        turn = turn.opposite();
        if (consecutivePasses >= 2) {
            gameOver = true;
        }
        return MoveResult.accepted(lastMove, List.of());
    }

    public void resign(Stone color) {
        if (color != Stone.BLACK && color != Stone.WHITE) {
            throw new IllegalArgumentException("只有黑棋或白棋可以认输");
        }
        if (gameOver) return;
        resignedBy = color;
        gameOver = true;
    }

    public Stone resignedBy() {
        return resignedBy;
    }

    public boolean isLegal(int x, int y) {
        return copy().play(x, y).success();
    }

    public List<Point> legalPoints() {
        List<Point> legal = new ArrayList<>();
        if (gameOver) {
            return legal;
        }
        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                if (board[x][y] == Stone.EMPTY && isLegal(x, y)) {
                    legal.add(new Point(x, y));
                }
            }
        }
        return legal;
    }

    public int libertiesAt(int x, int y) {
        if (!isInside(x, y) || board[x][y] == Stone.EMPTY) {
            return 0;
        }
        return liberties(board, groupAt(board, x, y)).size();
    }

    public ScoreEstimate estimateScore() {
        boolean[][] visited = new boolean[size][size];
        double black = 0;
        double white = komi;

        for (int x = 0; x < size; x++) {
            for (int y = 0; y < size; y++) {
                Stone stone = board[x][y];
                if (stone == Stone.BLACK) {
                    black++;
                } else if (stone == Stone.WHITE) {
                    white++;
                } else if (!visited[x][y]) {
                    Set<Point> region = emptyRegion(x, y, visited);
                    Set<Stone> borders = new HashSet<>();
                    for (Point point : region) {
                        for (Point neighbor : neighbors(point.x(), point.y())) {
                            Stone border = board[neighbor.x()][neighbor.y()];
                            if (border != Stone.EMPTY) {
                                borders.add(border);
                            }
                        }
                    }
                    if (borders.size() == 1) {
                        if (borders.contains(Stone.BLACK)) {
                            black += region.size();
                        } else {
                            white += region.size();
                        }
                    }
                }
            }
        }
        return new ScoreEstimate(black, white, komi);
    }

    private Set<Point> emptyRegion(int startX, int startY, boolean[][] visited) {
        Set<Point> region = new HashSet<>();
        Queue<Point> queue = new ArrayDeque<>();
        queue.add(new Point(startX, startY));
        visited[startX][startY] = true;
        while (!queue.isEmpty()) {
            Point point = queue.remove();
            region.add(point);
            for (Point neighbor : neighbors(point.x(), point.y())) {
                if (!visited[neighbor.x()][neighbor.y()]
                        && board[neighbor.x()][neighbor.y()] == Stone.EMPTY) {
                    visited[neighbor.x()][neighbor.y()] = true;
                    queue.add(neighbor);
                }
            }
        }
        return region;
    }

    private Set<Point> groupAt(Stone[][] position, int startX, int startY) {
        Stone color = position[startX][startY];
        Set<Point> group = new HashSet<>();
        Queue<Point> queue = new ArrayDeque<>();
        Point start = new Point(startX, startY);
        group.add(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            Point point = queue.remove();
            for (Point neighbor : neighbors(point.x(), point.y())) {
                if (position[neighbor.x()][neighbor.y()] == color && group.add(neighbor)) {
                    queue.add(neighbor);
                }
            }
        }
        return group;
    }

    private Set<Point> liberties(Stone[][] position, Set<Point> group) {
        Set<Point> result = new HashSet<>();
        for (Point point : group) {
            for (Point neighbor : neighbors(point.x(), point.y())) {
                if (position[neighbor.x()][neighbor.y()] == Stone.EMPTY) {
                    result.add(neighbor);
                }
            }
        }
        return result;
    }

    private List<Point> neighbors(int x, int y) {
        List<Point> result = new ArrayList<>(4);
        if (x > 0) result.add(new Point(x - 1, y));
        if (x + 1 < size) result.add(new Point(x + 1, y));
        if (y > 0) result.add(new Point(x, y - 1));
        if (y + 1 < size) result.add(new Point(x, y + 1));
        return result;
    }

    private boolean isInside(int x, int y) {
        return x >= 0 && x < size && y >= 0 && y < size;
    }

    private static Stone[][] emptyBoard(int size) {
        Stone[][] result = new Stone[size][size];
        for (Stone[] column : result) {
            Arrays.fill(column, Stone.EMPTY);
        }
        return result;
    }

    private static Stone[][] copyBoard(Stone[][] source) {
        Stone[][] result = new Stone[source.length][source.length];
        for (int x = 0; x < source.length; x++) {
            result[x] = source[x].clone();
        }
        return result;
    }

    private String encode(Stone[][] position) {
        StringBuilder result = new StringBuilder(size * size);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                result.append(switch (position[x][y]) {
                    case BLACK -> 'B';
                    case WHITE -> 'W';
                    case EMPTY -> '.';
                });
            }
        }
        return result.toString();
    }

    public int size() {
        return size;
    }

    public double komi() {
        return komi;
    }

    public Stone stoneAt(int x, int y) {
        if (!isInside(x, y)) {
            return Stone.EMPTY;
        }
        return board[x][y];
    }

    public Stone turn() {
        return turn;
    }

    public int moveNumber() {
        return moveNumber;
    }

    public int consecutivePasses() {
        return consecutivePasses;
    }

    public int blackCaptures() {
        return blackCaptures;
    }

    public int whiteCaptures() {
        return whiteCaptures;
    }

    public Move lastMove() {
        return lastMove;
    }

    public boolean gameOver() {
        return gameOver;
    }

    public String asciiDiagram() {
        StringBuilder text = new StringBuilder();
        for (int y = 0; y < size; y++) {
            int row = size - y;
            text.append(String.format("%2d ", row));
            for (int x = 0; x < size; x++) {
                text.append(switch (board[x][y]) {
                    case BLACK -> 'X';
                    case WHITE -> 'O';
                    case EMPTY -> '.';
                });
                if (x + 1 < size) text.append(' ');
            }
            text.append('\n');
        }
        String columns = "ABCDEFGHJKLMNOPQRST";
        text.append("   ");
        for (int x = 0; x < size; x++) {
            if (x > 0) text.append(' ');
            text.append(columns.charAt(x));
        }
        return text.toString();
    }
}
