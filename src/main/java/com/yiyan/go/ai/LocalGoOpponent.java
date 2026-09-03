package com.yiyan.go.ai;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;
import java.util.Comparator;
import java.util.List;

/** A small transparent heuristic, not a professional Go engine. */
public final class LocalGoOpponent implements GoOpponent {
    @Override
    public AiDecision chooseMove(BoardState position) throws InterruptedException {
        Thread.sleep(360);
        return chooseWithoutDelay(position);
    }

    static AiDecision chooseWithoutDelay(BoardState position) {
        MovePolicy.PassAssessment pass = MovePolicy.assessPass(position);
        if (pass.allowed()) return AiDecision.pass(pass.reason());
        List<MovePolicy.Assessment> candidates = MovePolicy.candidates(position);
        MovePolicy.Assessment chosen = candidates.stream()
                .max(Comparator.comparingDouble(a -> score(position, a))).orElseThrow();
        return AiDecision.play(chosen.point(), "本地陪练选择 " + chosen.point().coordinate(position.size())
                + "。" + chosen.summary() + " 这里只核对一手可见事实，不据此判断胜负。");
    }

    private static double score(BoardState position, MovePolicy.Assessment candidate) {
        Point point = candidate.point();
        double value = candidate.captured() * 160.0 + candidate.rescuedGroups() * 100.0;
        value += Math.max(0, candidate.connectedGroups() - 1) * 12.0;
        value += Math.min(candidate.libertiesAfter(), 8) * 2.0;
        if (candidate.selfAtari()) value -= 100.0;
        int edgeX = Math.min(point.x(), position.size() - 1 - point.x());
        int edgeY = Math.min(point.y(), position.size() - 1 - point.y());
        int preferredLine = position.size() == 9 ? 2 : 3;
        if (position.moveNumber() < position.size() * 2) {
            if (edgeX == preferredLine && edgeY == preferredLine) value += 30;
            else if (edgeX >= 2 && edgeY >= 2) value += 7;
        }
        if (edgeX == 0 || edgeY == 0) value -= 8;
        value += adjacent(position, point, position.turn().opposite()) * 4;
        value += Math.floorMod(point.x() * 37 + point.y() * 23 + position.moveNumber() * 11, 29) / 100.0;
        return value;
    }

    private static int adjacent(BoardState board, Point point, Stone color) {
        int count = 0;
        for (int[] direction : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
            if (board.stoneAt(point.x() + direction[0], point.y() + direction[1]) == color) count++;
        }
        return count;
    }

    @Override
    public String displayName() { return "本地陪练"; }
}
