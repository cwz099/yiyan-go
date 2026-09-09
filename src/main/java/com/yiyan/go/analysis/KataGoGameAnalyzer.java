package com.yiyan.go.analysis;

import com.yiyan.go.ai.MovePolicy;
import com.yiyan.go.engine.KataGoConfig;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.Move;
import com.yiyan.go.game.Stone;
import com.yiyan.go.recording.GameRecord;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/** Converts KataGo position values into a whole-game coaching report for the human player. */
public final class KataGoGameAnalyzer {
    private final KataGoAnalysisEngine engine;

    public KataGoGameAnalyzer(KataGoConfig config) { this(new KataGoAnalysisEngine(config)); }
    KataGoGameAnalyzer(KataGoAnalysisEngine engine) { this.engine = Objects.requireNonNull(engine); }

    public GameReview analyze(GameRecord game) throws Exception {
        Objects.requireNonNull(game);
        long started = System.nanoTime();
        int visits = visitsFor(game.moves().size());
        Map<Integer, KataGoAnalysisEngine.PositionValue> positions = engine.analyze(game, visits);
        return compose(game, positions, visits,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started));
    }

    static int visitsFor(int moves) {
        if (moves <= 80) return 24;
        if (moves <= 180) return 12;
        return 8;
    }

    static GameReview compose(GameRecord game,
                              Map<Integer, KataGoAnalysisEngine.PositionValue> positions,
                              int visits, long elapsedMs) {
        List<GameReview.TurnInsight> all = new ArrayList<>();
        Map<Integer, GameReview.TurnInsight> byMove = new LinkedHashMap<>();
        for (int index = 0; index < game.moves().size(); index++) {
            KataGoAnalysisEngine.PositionValue before = required(positions, index);
            KataGoAnalysisEngine.PositionValue after = required(positions, index + 1);
            GameRecord.Ply ply = game.moves().get(index);
            Move played = ply.move();
            String actualCoordinate = played.pass() ? "PASS" : played.coordinate(game.size());
            KataGoAnalysisEngine.MoveValue best = before.best();
            KataGoAnalysisEngine.MoveValue actual = before.moves().get(actualCoordinate);

            double pointLoss = 0;
            double winrateLoss = 0;
            String bestMove = "";
            List<String> variation = List.of();
            if (best != null) {
                bestMove = best.move();
                variation = best.variation();
                double actualLead = actual == null ? after.blackLead() : actual.blackLead();
                double actualWinrate = actual == null ? after.blackWinrate() : actual.blackWinrate();
                double sign = played.stone() == Stone.BLACK ? 1 : -1;
                pointLoss = Math.max(0, sign * (best.blackLead() - actualLead));
                winrateLoss = Math.max(0, sign * (best.blackWinrate() - actualWinrate));
                if (actualCoordinate.equals(best.move())) {
                    pointLoss = 0;
                    winrateLoss = 0;
                }
            }
            if (pointLoss < 0.35) pointLoss = 0;
            if (winrateLoss < 0.002) winrateLoss = 0;
            pointLoss = Math.min(pointLoss, game.size() * game.size() * 2.0);
            winrateLoss = Math.min(winrateLoss, 1);
            boolean human = played.stone() == game.humanColor();
            GameReview.TurnInsight insight = new GameReview.TurnInsight(played.number(), played.stone(),
                    actualCoordinate, bestMove, pointLoss, winrateLoss,
                    phase(played.number(), game.size()), severity(pointLoss),
                    guidance(game.positionAt(index), ply, bestMove), variation, human);
            all.add(insight);
            byMove.put(played.number(), insight);
        }

        Comparator<GameReview.TurnInsight> largest = Comparator.comparingDouble(GameReview.TurnInsight::pointLoss)
                .reversed().thenComparingInt(GameReview.TurnInsight::moveNumber);
        List<GameReview.TurnInsight> humanMistakes = all.stream().filter(GameReview.TurnInsight::humanMove)
                .filter(item -> item.pointLoss() >= 1).sorted(largest).limit(5).toList();
        List<GameReview.TurnInsight> opportunities = all.stream().filter(item -> !item.humanMove())
                .filter(item -> item.pointLoss() >= 1).sorted(largest).limit(3).toList();

        String headline = headline(game, humanMistakes, opportunities);
        String overview = overview(game, positions, humanMistakes, opportunities);
        List<String> tips = trainingTips(game, humanMistakes);
        return new GameReview(headline, overview, humanMistakes, opportunities, tips,
                byMove, visits, elapsedMs);
    }

    private static KataGoAnalysisEngine.PositionValue required(
            Map<Integer, KataGoAnalysisEngine.PositionValue> positions, int turn) {
        KataGoAnalysisEngine.PositionValue value = positions.get(turn);
        if (value == null) throw new IllegalArgumentException("缺少第 " + turn + " 手局面分析");
        return value;
    }

    private static String headline(GameRecord game, List<GameReview.TurnInsight> mistakes,
                                   List<GameReview.TurnInsight> opportunities) {
        Stone winner = winner(game);
        if (winner == game.humanColor()) {
            return opportunities.isEmpty() ? "你赢下了这盘棋，优势来自持续积累"
                    : "你赢下了这盘棋，最大机会出现在对手第 " + opportunities.get(0).moveNumber() + " 手";
        }
        if (winner == game.humanColor().opposite()) {
            return mistakes.isEmpty() ? "这盘失利没有单一崩盘点，差距由多处细节累积"
                    : "这盘棋的首要复盘点是第 " + mistakes.get(0).moveNumber() + " 手";
        }
        if (winner == Stone.EMPTY && game.finalScore() != null) return "这盘棋最终战成和棋，重点看机会交换";
        return mistakes.isEmpty() ? "本局尚未结算，先检查整盘节奏"
                : "本局尚未结算，当前最大失分在第 " + mistakes.get(0).moveNumber() + " 手";
    }

    private static String overview(GameRecord game,
                                   Map<Integer, KataGoAnalysisEngine.PositionValue> positions,
                                   List<GameReview.TurnInsight> mistakes,
                                   List<GameReview.TurnInsight> opportunities) {
        double sign = game.humanColor() == Stone.BLACK ? 1 : -1;
        int bestTurn = 0, worstTurn = 0;
        double bestLead = -Double.MAX_VALUE, worstLead = Double.MAX_VALUE;
        for (Map.Entry<Integer, KataGoAnalysisEngine.PositionValue> entry : positions.entrySet()) {
            double lead = sign * entry.getValue().blackLead();
            if (lead > bestLead) { bestLead = lead; bestTurn = entry.getKey(); }
            if (lead < worstLead) { worstLead = lead; worstTurn = entry.getKey(); }
        }
        String result = game.result().isBlank() ? game.status().label() : game.result();
        StringBuilder text = new StringBuilder("结果：").append(result).append("\n")
                .append("走势：你在").append(positionName(bestTurn)).append("达到全局最好估值（")
                .append(leadText(bestLead)).append("），在").append(positionName(worstTurn))
                .append("处于最低估值（").append(leadText(worstLead)).append("）。");
        if (!mistakes.isEmpty()) {
            GameReview.TurnInsight top = mistakes.get(0);
            text.append("\n输赢转折：你的最大单手损失出现在第 ").append(top.moveNumber())
                    .append(" 手 ").append(top.playedMove()).append("，约 ")
                    .append(GameReview.number(top.pointLoss())).append(" 目。");
        }
        if (!opportunities.isEmpty()) {
            GameReview.TurnInsight top = opportunities.get(0);
            text.append("\n机会来源：对手第 ").append(top.moveNumber()).append(" 手 ")
                    .append(top.playedMove()).append(" 给出了约 ")
                    .append(GameReview.number(top.pointLoss())).append(" 目的机会。");
        }
        return text.toString();
    }

    private static List<String> trainingTips(GameRecord game,
                                             List<GameReview.TurnInsight> mistakes) {
        Set<String> tips = new LinkedHashSet<>();
        if (!mistakes.isEmpty()) {
            Map<String, Double> phaseLoss = new LinkedHashMap<>();
            for (GameReview.TurnInsight item : mistakes) phaseLoss.merge(item.phase(), item.pointLoss(), Double::sum);
            String phase = phaseLoss.entrySet().stream().max(Map.Entry.comparingByValue()).orElseThrow().getKey();
            tips.add(switch (phase) {
                case "开局" -> "开局训练全局方向：每手先比较角、边、中央以及双方弱棋，避免只追着局部走。";
                case "中盘" -> "中盘训练候选点和读棋：每个关键局面先列出进攻、补强、抢先手三个候选。";
                default -> "收官训练目数比较：计算双方先手官子，并在停一手前逐块确认死活和边界。";
            });
            tips.add(mistakes.get(0).guidance());
        } else {
            tips.add("保持当前稳定度；把胜率接近的局面重新摆一次，强迫自己提出至少三个候选点。重在解释选择，而非背答案。");
        }
        long passErrors = mistakes.stream().filter(item -> item.playedMove().equals("PASS")).count();
        if (passErrors > 0) tips.add("专项练习停一手判断：检查未定型区域、弱棋气数和最大官子，三项都处理完再停手。");
        long rushed = mistakes.stream().filter(item -> game.moves().get(item.moveNumber() - 1).elapsedMs() < 10_000).count();
        if (rushed >= 2) tips.add("多处失分发生在十秒内：关键处执行“对方威胁—我的弱棋—最大落点”三步检查后再落子。");
        tips.add("复摆方法：先停在最大失分手之前，隐藏推荐点，自选三种下法并写出目的，再对照 KataGo 的推荐与参考变化。");
        return tips.stream().limit(4).toList();
    }

    private static String guidance(BoardState before, GameRecord.Ply ply, String bestMove) {
        if (ply.move().pass()) return "停一手前先检查未定型区域、双方弱棋和最大官子。";
        MovePolicy.Assessment assessment = MovePolicy.analyze(before, ply.move().point());
        if (assessment.fillsOwnEye()) return "避免无收益地填自己的眼；先寻找外部先手或补住真正的断点。";
        if (assessment.selfAtari()) return "落子前数清己方棋块的气，并阅读对方紧气后的应对。";
        if (!ply.captured().isEmpty()) return "提子不一定等于获利；比较提子后全局先手、厚薄和推荐点的目数。";
        if (assessment.libertiesAfter() <= 2) return "这手附近棋块较紧，先确认出路和连接，再决定是否投入更多棋子。";
        return phase(ply.move().number(), before.size()).equals("开局")
                ? "扩大视野，落子前比较四个方向的价值与双方弱棋。"
                : "先列出三个候选点，比较先手、棋块安全和全局目数后再选择。";
    }

    private static String severity(double loss) {
        if (loss >= 8) return "关键失误";
        if (loss >= 4) return "明显失分";
        if (loss >= 1.5) return "小缓手";
        if (loss >= 0.75) return "轻微失分";
        return "较稳";
    }

    private static String phase(int move, int size) {
        int opening = size == 19 ? 30 : size == 13 ? 16 : 10;
        int middle = size == 19 ? 120 : size == 13 ? 65 : 35;
        return move <= opening ? "开局" : move <= middle ? "中盘" : "收官";
    }

    private static Stone winner(GameRecord game) {
        if (game.finalScore() != null) return game.finalScore().winner();
        Stone resigned = game.finalPosition().resignedBy();
        return resigned == null ? null : resigned.opposite();
    }

    private static String positionName(int turn) { return turn == 0 ? "开局前" : "第 " + turn + " 手后"; }
    private static String leadText(double lead) {
        return (lead >= 0 ? "约领先 " : "约落后 ") + GameReview.number(Math.abs(lead)) + " 目";
    }
}
