package com.yiyan.go.game;

import java.util.List;

public record MoveResult(boolean success, String message, Move move, List<Point> captured) {
    public static MoveResult rejected(String message) {
        return new MoveResult(false, message, null, List.of());
    }

    public static MoveResult accepted(Move move, List<Point> captured) {
        return new MoveResult(true, "", move, List.copyOf(captured));
    }
}
