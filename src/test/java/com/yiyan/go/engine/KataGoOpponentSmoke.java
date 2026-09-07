package com.yiyan.go.engine;

import com.yiyan.go.ai.AiDecision;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.GoDifficulty;
import com.yiyan.go.game.Move;

import java.util.ArrayList;
import java.util.List;

/** Explicit real-engine check for ranked play. */
public final class KataGoOpponentSmoke {
    private KataGoOpponentSmoke() { }

    public static void main(String[] args) throws Exception {
        BoardState board = new BoardState(9);
        List<Move> history = new ArrayList<>();
        history.add(board.play(0, 0).move());
        long started = System.nanoTime();
        try (KataGoOpponent opponent = new KataGoOpponent(KataGoConfig.discover(), GoDifficulty.THREE)) {
            AiDecision first = opponent.chooseMove(board, history);
            history.add(board.play(first.point().x(), first.point().y()).move());
            history.add(board.play(1, 0).move());
            AiDecision second = opponent.chooseMove(board, history);
            System.out.println(opponent.displayName() + " | " + coordinate(first, board.size())
                    + " -> " + coordinate(second, board.size()));
            System.out.println(first.motivation());
        }
        System.out.println("durationMs=" + ((System.nanoTime() - started) / 1_000_000));
    }

    private static String coordinate(AiDecision decision, int size) {
        if (decision.pass()) return "PASS";
        return "ABCDEFGHJKLMNOPQRST".charAt(decision.point().x())
                + Integer.toString(size - decision.point().y());
    }
}
