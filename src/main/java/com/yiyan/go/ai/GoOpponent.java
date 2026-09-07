package com.yiyan.go.ai;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.Move;
import java.util.List;

public interface GoOpponent extends AutoCloseable {
    AiDecision chooseMove(BoardState position) throws Exception;

    default AiDecision chooseMove(BoardState position, List<Move> history) throws Exception {
        return chooseMove(position);
    }

    String displayName();

    @Override default void close() { }
}
