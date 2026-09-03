package com.yiyan.go.ui;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

class LogsDialogTest {
    @TempDir Path directory;

    @Test void missingFileIsExplained() throws Exception {
        assertTrue(LogsDialog.readTail(directory.resolve("missing"), 100).contains("尚无"));
    }

    @Test void tailDropsTruncatedUnicodeLine() throws Exception {
        Path log = directory.resolve("test.jsonl");
        Files.writeString(log, "很长的第一条记录\n第二条\n第三条\n");
        String tail = LogsDialog.readTail(log, 23);
        assertEquals("第二条\n第三条\n", tail);
        assertFalse(tail.contains("�"));
    }

    @Test void zeroKomiTieHasNoWinner() {
        var score = new com.yiyan.go.game.ScoreEstimate(5, 5, 0);
        assertEquals(com.yiyan.go.game.Stone.EMPTY, score.winner());
    }
}
