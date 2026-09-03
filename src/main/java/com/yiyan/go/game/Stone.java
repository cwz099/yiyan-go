package com.yiyan.go.game;

public enum Stone {
    EMPTY,
    BLACK,
    WHITE;

    public Stone opposite() {
        return switch (this) {
            case BLACK -> WHITE;
            case WHITE -> BLACK;
            case EMPTY -> EMPTY;
        };
    }

    public String chineseName() {
        return switch (this) {
            case BLACK -> "黑棋";
            case WHITE -> "白棋";
            case EMPTY -> "空点";
        };
    }
}
