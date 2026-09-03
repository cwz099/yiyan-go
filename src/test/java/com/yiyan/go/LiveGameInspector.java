package com.yiyan.go;

import com.yiyan.go.ai.MovePolicy;
import com.yiyan.go.recording.GameArchive;
import com.yiyan.go.recording.GameRecord;
import java.nio.file.Path;
import java.util.Comparator;

/** Read-only aid for a live UI acceptance game. Never places stones or calls an API. */
public final class LiveGameInspector {
    public static void main(String[] args) throws Exception {
        GameRecord record = args.length > 0 ? GameArchive.load(Path.of(args[0]))
                : GameArchive.list().games().stream().max(Comparator.comparing(GameRecord::updated)).orElseThrow();
        var board = record.finalPosition();
        System.out.println("GAME " + record.id() + " | ply=" + board.moveNumber() + " | turn=" + board.turn()
                + " | " + record.status() + " | captures=" + board.blackCaptures() + "/" + board.whiteCaptures());
        System.out.println(board.asciiDiagram());
        if (!board.gameOver()) {
            var pass = MovePolicy.assessPass(board);
            System.out.println("PASS " + pass.allowed() + " " + pass.reason());
            MovePolicy.candidates(board).stream().sorted(Comparator
                    .<MovePolicy.Assessment>comparingInt(a -> a.captured() * 100 + a.rescuedGroups() * 50
                            + a.connectedGroups() * 3 + a.libertiesAfter()).reversed())
                    .limit(12).forEach(a -> System.out.println(a.point().coordinate(board.size()) + " " + a.summary()));
        }
        System.out.println("Remote moves: " + record.moves().stream()
                .filter(p -> p.source().startsWith("DeepSeek")).count());
        System.out.println("Fallback moves: " + record.moves().stream()
                .filter(p -> p.source().contains("本地接续")).count());
    }
}
