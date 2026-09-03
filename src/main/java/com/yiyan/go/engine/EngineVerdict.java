package com.yiyan.go.engine;

import com.yiyan.go.game.FinalScore;

/** Engine dead-group adjudication plus an independently reproducible area count. */
public record EngineVerdict(String engine, String model, String rawScore, FinalScore score,
                            long elapsedMs, boolean consistent) {
    public String source() { return engine + " · " + model; }
}
