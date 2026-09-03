package com.yiyan.go.ai;

import com.yiyan.go.game.BoardState;

public interface GoOpponent {
    AiDecision chooseMove(BoardState position) throws Exception;

    String displayName();
}
