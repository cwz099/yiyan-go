package com.yiyan.go.ai;

import com.yiyan.go.game.Point;

public record AiDecision(Point point, boolean pass, String motivation, String requestId, int attempts) {
    public AiDecision(Point point, boolean pass, String motivation) {
        this(point, pass, motivation, "", 0);
    }

    public AiDecision withRequest(String requestId, int attempts) {
        return new AiDecision(point, pass, motivation, requestId, attempts);
    }

    public static AiDecision play(Point point, String motivation) {
        return new AiDecision(point, false, motivation);
    }

    public static AiDecision pass(String motivation) {
        return new AiDecision(null, true, motivation);
    }
}
