package com.yiyan.go.engine;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.Move;
import java.util.List;

@FunctionalInterface
public interface ScoreEngine {
    EngineVerdict evaluate(BoardState board, List<Move> history) throws Exception;
}
