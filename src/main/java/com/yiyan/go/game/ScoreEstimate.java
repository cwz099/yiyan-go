package com.yiyan.go.game;

public record ScoreEstimate(double black, double white, double komi) {
    public Stone winner() {
        return black == white ? Stone.EMPTY : black > white ? Stone.BLACK : Stone.WHITE;
    }

    public double margin() {
        return Math.abs(black - white);
    }
}
