package com.yiyan.go.game;

import java.util.Locale;
import java.util.Set;

/** A Chinese area count after a human has chosen dead groups and any disputed regions. */
public record FinalScore(int boardSize, Set<Point> deadStones, Set<Point> neutralPoints,
                         int blackStones, int whiteStones, int blackTerritory,
                         int whiteTerritory, int neutralCount, double komi) {
    public FinalScore {
        deadStones = Set.copyOf(deadStones);
        neutralPoints = Set.copyOf(neutralPoints);
        if (boardSize < 5 || boardSize > 19 || blackStones < 0 || whiteStones < 0
                || blackTerritory < 0 || whiteTerritory < 0 || neutralCount < 0
                || (long) blackStones + whiteStones + blackTerritory + whiteTerritory + neutralCount
                != (long) boardSize * boardSize
                || !Double.isFinite(komi) || komi < 0 || komi > 30) {
            throw new IllegalArgumentException("结算数据不完整或超出范围");
        }
        for (Point point : deadStones) checkPoint(point, boardSize);
        for (Point point : neutralPoints) checkPoint(point, boardSize);
        if (neutralPoints.size() > neutralCount) {
            throw new IllegalArgumentException("中立标记不能多于中立点数");
        }
    }

    private static void checkPoint(Point point, int size) {
        if (point.x() < 0 || point.y() < 0 || point.x() >= size || point.y() >= size) {
            throw new IllegalArgumentException("结算标记超出棋盘");
        }
    }

    public double blackTotal() { return blackStones + blackTerritory; }
    public double whiteTotal() { return whiteStones + whiteTerritory + komi; }
    public Stone winner() {
        return blackTotal() == whiteTotal() ? Stone.EMPTY
                : blackTotal() > whiteTotal() ? Stone.BLACK : Stone.WHITE;
    }
    public double margin() { return Math.abs(blackTotal() - whiteTotal()); }

    public String summary() {
        String result = winner() == Stone.EMPTY ? "和棋"
                : winner().chineseName() + "胜 " + format(margin()) + " 点";
        return "已确认结算 · " + result + "（黑 " + format(blackTotal())
                + "，白 " + format(whiteTotal()) + "，白含贴目 " + format(komi) + "）";
    }

    public static String format(double value) {
        return String.format(Locale.ROOT, "%.1f", value).replaceFirst("\\.0$", "");
    }
}
