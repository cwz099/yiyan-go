package com.yiyan.go.analysis;

import com.yiyan.go.engine.KataGoConfig;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.MoveResult;
import com.yiyan.go.game.Stone;
import com.yiyan.go.recording.GameArchive;
import com.yiyan.go.recording.GameRecord;
import com.yiyan.go.recording.GameRecorder;

import java.nio.file.Files;
import java.nio.file.Path;

/** Explicit real-engine smoke check; not part of the normal unit-test run. */
public final class KataGoGameAnalysisSmoke {
    private KataGoGameAnalysisSmoke() { }

    public static void main(String[] args) throws Exception {
        Path data = Path.of("target", "analysis-smoke-data").toAbsolutePath();
        Files.createDirectories(data);
        System.setProperty("yiyan.dataDir", data.toString());
        BoardState board = new BoardState(9, 6.5);
        GameRecorder recorder = GameRecorder.start(board, Stone.BLACK, "KataGo · 真实引擎测试");
        play(recorder, board, 2, 2);
        play(recorder, board, 6, 6);
        play(recorder, board, 6, 2);
        play(recorder, board, 2, 6);
        play(recorder, board, 4, 2);
        play(recorder, board, 4, 6);
        board.resign(Stone.WHITE);
        recorder.finish(board, "白棋认输，黑棋获胜");
        if (!recorder.error().isBlank()) throw new IllegalStateException(recorder.error());
        GameRecord game = GameArchive.list().games().stream()
                .filter(item -> item.id().equals(recorder.id())).findFirst().orElseThrow();

        GameReview review = new KataGoGameAnalyzer(KataGoConfig.discover()).analyze(game);
        System.out.println(review.formatted());
        if (review.turns().size() != game.moves().size() || review.trainingTips().isEmpty()) {
            throw new IllegalStateException("Incomplete whole-game report");
        }
    }

    private static void play(GameRecorder recorder, BoardState board, int x, int y) {
        MoveResult result = board.play(x, y);
        if (!result.success()) throw new IllegalStateException(result.message());
        recorder.record(board, result, "真实引擎测试", "候选点", 2_000);
    }
}
