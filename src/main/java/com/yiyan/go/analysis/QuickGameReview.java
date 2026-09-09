package com.yiyan.go.analysis;

import com.yiyan.go.game.Stone;
import com.yiyan.go.recording.GameRecord;

import java.util.Comparator;

/** Instant factual overview shown before the user requests engine analysis. */
public final class QuickGameReview {
    private QuickGameReview() { }

    public static String format(GameRecord game) {
        if (game == null) return "选择一盘棋后，这里会总结整盘走势。";
        long humanMoves = game.moves().stream().filter(ply -> ply.move().stone() == game.humanColor()).count();
        int humanCaptures = game.moves().stream().filter(ply -> ply.move().stone() == game.humanColor())
                .mapToInt(ply -> ply.captured().size()).sum();
        int opponentCaptures = game.moves().stream().filter(ply -> ply.move().stone() != game.humanColor())
                .mapToInt(ply -> ply.captured().size()).sum();
        long passes = game.moves().stream().filter(ply -> ply.move().pass()).count();
        var longest = game.moves().stream().filter(ply -> ply.move().stone() == game.humanColor())
                .max(Comparator.comparingLong(GameRecord.Ply::elapsedMs)).orElse(null);

        StringBuilder text = new StringBuilder("整盘基础概览\n\n")
                .append("结果：").append(outcome(game)).append("\n")
                .append("你执").append(game.humanColor().chineseName()).append("，共下 ").append(humanMoves)
                .append(" 手；你提掉 ").append(humanCaptures).append(" 子，对手提掉 ")
                .append(opponentCaptures).append(" 子；全局停一手 ").append(passes).append(" 次。\n");
        if (longest != null) {
            text.append("思考最久：第 ").append(longest.move().number()).append(" 手 ")
                    .append(longest.move().coordinate(game.size())).append("，")
                    .append(longest.elapsedMs()).append(" ms。\n");
        }
        text.append("\n这份概览只陈述棋谱事实，不判断单手好坏。点击上方“KataGo 分析整盘”，")
                .append("可定位胜负转折、主要失分、对手送出的机会和训练重点。");
        return text.toString();
    }

    private static String outcome(GameRecord game) {
        if (!game.result().isBlank()) return game.result();
        Stone resigned = game.finalPosition().resignedBy();
        if (resigned != null) return resigned == game.humanColor() ? "你已认输" : "对手已认输";
        return game.status().label();
    }
}
