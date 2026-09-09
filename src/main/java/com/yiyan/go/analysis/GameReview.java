package com.yiyan.go.analysis;

import com.yiyan.go.game.Stone;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Immutable, engine-backed review of one complete main line. */
public record GameReview(String headline, String overview,
                         List<TurnInsight> humanMistakes,
                         List<TurnInsight> opportunities,
                         List<String> trainingTips,
                         Map<Integer, TurnInsight> turns,
                         int visitsPerPosition, long elapsedMs) {
    public GameReview {
        headline = safe(headline);
        overview = safe(overview);
        humanMistakes = List.copyOf(humanMistakes);
        opportunities = List.copyOf(opportunities);
        trainingTips = List.copyOf(trainingTips);
        turns = Map.copyOf(turns);
        if (visitsPerPosition < 1 || elapsedMs < 0) throw new IllegalArgumentException("复盘参数无效");
    }

    public String formatted() {
        StringBuilder text = new StringBuilder(headline).append("\n\n").append(overview);
        section(text, "你最需要复盘的几手", humanMistakes,
                "没有发现超过快速分析阈值的明显失分。仍建议复摆关键战斗，比较至少三个候选点。");
        section(text, "你赢得或重新获得的机会", opportunities,
                "没有发现对手单手送出的大幅机会；这盘棋更像是长期细小差距的累积。");
        text.append("\n\n—— 下一步怎么练 ——");
        for (int i = 0; i < trainingTips.size(); i++) {
            text.append("\n").append(i + 1).append(". ").append(trainingTips.get(i));
        }
        text.append("\n\n分析依据：KataGo 快速全盘分析，每个局面最多 ")
                .append(visitsPerPosition).append(" 次搜索。目数与胜率是估值，适合定位复盘重点，不等同于裁判结果。")
                .append("\n耗时：").append(elapsedMs).append(" ms");
        return text.toString();
    }

    public String detailForMove(int moveNumber) {
        TurnInsight insight = turns.get(moveNumber);
        return insight == null ? "" : insight.detail();
    }

    private static void section(StringBuilder text, String title, List<TurnInsight> insights, String empty) {
        text.append("\n\n—— ").append(title).append(" ——");
        if (insights.isEmpty()) {
            text.append("\n").append(empty);
            return;
        }
        for (int i = 0; i < insights.size(); i++) {
            text.append("\n").append(i + 1).append(". ").append(insights.get(i).summary());
        }
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }

    public record TurnInsight(int moveNumber, Stone player, String playedMove, String bestMove,
                              double pointLoss, double winrateLoss, String phase,
                              String severity, String guidance, List<String> variation,
                              boolean humanMove) {
        public TurnInsight {
            if (moveNumber < 1 || player == null || player == Stone.EMPTY
                    || !Double.isFinite(pointLoss) || pointLoss < 0
                    || !Double.isFinite(winrateLoss) || winrateLoss < 0) {
                throw new IllegalArgumentException("单手复盘数据无效");
            }
            playedMove = safe(playedMove);
            bestMove = safe(bestMove);
            phase = safe(phase);
            severity = safe(severity);
            guidance = safe(guidance);
            variation = List.copyOf(variation);
        }

        public String summary() {
            return "第 " + moveNumber + " 手 " + player.chineseName() + " " + playedMove
                    + " · " + phase + " · " + severity + "，约损失 " + number(pointLoss)
                    + " 目" + (bestMove.isBlank() ? "" : "；优先考虑 " + bestMove)
                    + "。" + guidance;
        }

        public String detail() {
            StringBuilder text = pointLoss < 0.35
                    ? new StringBuilder("KataGo：较稳，快速分析未发现明显目数损失")
                    : new StringBuilder("KataGo：").append(severity)
                    .append("，约损失 ").append(number(pointLoss)).append(" 目");
            if (winrateLoss >= 0.005) text.append("，该方胜率约下降 ").append(percent(winrateLoss));
            if (!bestMove.isBlank()) text.append("。推荐先考虑 ").append(bestMove);
            if (!variation.isEmpty()) text.append("\n参考变化：").append(String.join(" → ", variation));
            if (!guidance.isBlank()) text.append("\n训练提示：").append(guidance);
            return text.toString();
        }
    }

    static String number(double value) {
        return String.format(Locale.ROOT, "%.1f", value).replaceFirst("\\.0$", "");
    }

    static String percent(double value) {
        return String.format(Locale.ROOT, "%.1f%%", value * 100);
    }
}
