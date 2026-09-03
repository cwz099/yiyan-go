package com.yiyan.go.game;

public record Move(Stone stone, Point point, boolean pass, int number) {
    public static Move play(Stone stone, Point point, int number) {
        return new Move(stone, point, false, number);
    }

    public static Move pass(Stone stone, int number) {
        return new Move(stone, null, true, number);
    }

    public String coordinate(int boardSize) {
        return pass ? "停一手" : point.coordinate(boardSize);
    }
}
