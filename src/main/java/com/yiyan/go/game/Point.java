package com.yiyan.go.game;

import java.util.Locale;

public record Point(int x, int y) {
    private static final String COLUMNS = "ABCDEFGHJKLMNOPQRST";

    public String coordinate(int boardSize) {
        if (x < 0 || x >= boardSize || y < 0 || y >= boardSize || x >= COLUMNS.length()) {
            return "--";
        }
        return COLUMNS.charAt(x) + Integer.toString(boardSize - y);
    }

    public static Point fromCoordinate(String raw, int boardSize) {
        if (raw == null) {
            throw new IllegalArgumentException("坐标不能为空");
        }
        String text = raw.trim().toUpperCase(Locale.ROOT);
        if (text.length() < 2) {
            throw new IllegalArgumentException("坐标格式不正确: " + raw);
        }
        int x = COLUMNS.indexOf(text.charAt(0));
        int row;
        try {
            row = Integer.parseInt(text.substring(1));
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("坐标格式不正确: " + raw, exception);
        }
        int y = boardSize - row;
        if (x < 0 || x >= boardSize || y < 0 || y >= boardSize) {
            throw new IllegalArgumentException("坐标超出棋盘: " + raw);
        }
        return new Point(x, y);
    }
}
