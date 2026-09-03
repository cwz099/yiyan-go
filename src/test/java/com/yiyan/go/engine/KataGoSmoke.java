package com.yiyan.go.engine;

import com.yiyan.go.recording.GameArchive;
import com.yiyan.go.recording.GameRecord;
import java.nio.file.Path;
import com.yiyan.go.game.*;
import java.util.ArrayList;
import java.util.List;

/** Explicit real-engine check. Reads but never changes the supplied saved game. */
public final class KataGoSmoke {
    public static void main(String[] args) throws Exception {
        GameRecord game = GameArchive.load(Path.of(args[0]));
        EngineVerdict verdict = new KataGoEngine(KataGoConfig.discover()).evaluate(game.finalPosition(),
                game.moves().stream().map(GameRecord.Ply::move).toList());
        System.out.println(verdict.engine() + " | " + verdict.model());
        System.out.println("GTP=" + verdict.rawScore() + " | consistent=" + verdict.consistent()
                + " | dead=" + verdict.score().deadStones().size() + " | durationMs=" + verdict.elapsedMs());
        System.out.println(verdict.score().summary());
        if (!verdict.consistent()) throw new IllegalStateException("Engine/count disagreement");
        for (int size : new int[]{9, 19}) {
            Fixture fixture = surroundedDeadStone(size);
            EngineVerdict dead = new KataGoEngine(KataGoConfig.discover()).evaluate(fixture.board(), fixture.history());
            System.out.println(size + "x" + size + " dead-stone fixture: " + dead.rawScore()
                    + " | dead=" + dead.score().deadStones().size() + " | consistent=" + dead.consistent()
                    + " | durationMs=" + dead.elapsedMs());
            if (!dead.consistent() || dead.score().deadStones().size() != 1
                    || dead.score().blackTotal() != size * size) throw new IllegalStateException("Dead-stone adjudication failed");
        }
    }

    public record Fixture(BoardState board, List<Move> history) { }
    public static Fixture surroundedDeadStone(int size) {
        BoardState board = new BoardState(size);
        List<Move> history = new ArrayList<>();
        int middle = size / 2;
        for (int y = 0; y < size; y++) for (int x = 0; x < size; x++) {
            if ((y == 1 && (x == 1 || x == 3)) || (x == middle && (y == middle || y == middle + 1))) continue;
            var black = board.play(x, y);
            if (!black.success()) throw new IllegalStateException(black.message());
            history.add(black.move());
            var white = history.size() == 1 ? board.play(middle, middle) : board.pass();
            if (!white.success()) throw new IllegalStateException(white.message());
            history.add(white.move());
        }
        history.add(board.pass().move());
        return new Fixture(board, List.copyOf(history));
    }
}
