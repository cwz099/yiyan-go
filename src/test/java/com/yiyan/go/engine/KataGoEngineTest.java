package com.yiyan.go.engine;

import com.yiyan.go.game.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class KataGoEngineTest {
    @TempDir Path directory;

    @Test void scoresAreColorAwareBoundedAndStrict() throws Exception {
        assertEquals(-35.5, KataGoEngine.parseWhiteLead("B+35.5", 9, 7.5));
        assertEquals(7.5, KataGoEngine.parseWhiteLead("W+7.5", 19, 7.5));
        assertEquals(0, KataGoEngine.parseWhiteLead("0", 9, 0));
        for (String value : new String[]{"W+NaN", "B+Infinity", "B+99", "W+R", "B+1\nquit", "= B+3.5"}) {
            assertThrows(IOException.class, () -> KataGoEngine.parseWhiteLead(value, 9, 7.5));
        }
    }

    @Test void deadCoordinatesRespectSkippedIAndTopToBottomRows() throws Exception {
        BoardState board = new BoardState(9);
        board.play(8, 0); // J9
        board.play(7, 8); // H1
        assertEquals(Set.of(new Point(8, 0)), KataGoEngine.parseDead(board, "J9"));
        assertEquals(Set.of(), KataGoEngine.parseDead(board, ""));
        assertEquals(Set.of(new Point(7, 8)), KataGoEngine.parseDead(board, "H1"));
        for (String value : new String[]{"I9", "A0", "A1", "T19", "PASS"}) {
            assertThrows(IOException.class, () -> KataGoEngine.parseDead(board, value));
        }
    }

    @Test void partialDeadGroupsAreNotSilentlyExpanded() throws Exception {
        BoardState board = new BoardState(9);
        board.play(2, 2); board.play(8, 8); board.play(3, 2);
        assertThrows(IOException.class, () -> KataGoEngine.parseDead(board, "C7"));
        assertEquals(2, KataGoEngine.parseDead(board, "C7 D7").size());
    }

    @Test void absentEngineIsAnExplicitFailure() {
        assertThrows(IOException.class, () -> new KataGoConfig(directory.resolve("missing.exe"),
                directory.resolve("missing.gz"), directory.resolve("missing.cfg")).validate());
    }

    private ProcessBuilder fake(String mode) {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", Path.of("target/test-classes").toAbsolutePath().toString(), FakeGtp.class.getName(), mode);
    }

    @Test void protocolReadsMultilineAndEmptyResponses() throws Exception {
        try (GtpSession session = new GtpSession(fake("ok"), Duration.ofSeconds(5))) {
            assertEquals("C7\nD7", session.command("dead"));
            assertEquals("", session.command("empty"));
            assertThrows(IOException.class, () -> session.command("bad\nquit"));
        }
    }

    @Test void mismatchedResponseIdAndEngineErrorsAreRejected() throws Exception {
        for (String mode : new String[]{"wrongid", "error", "exit"}) {
            try (GtpSession session = new GtpSession(fake(mode), Duration.ofSeconds(5))) {
                assertThrows(IOException.class, () -> session.command("dead"));
            }
        }
    }

    @Test void timeoutIsBoundedAndClosesTheProcess() {
        assertTimeoutPreemptively(Duration.ofSeconds(4), () -> {
            try (GtpSession session = new GtpSession(fake("hang"), Duration.ofMillis(300))) {
                assertThrows(IOException.class, () -> session.command("dead"));
            }
        });
    }

    @Test void cancellationInterruptsPendingReads() throws Exception {
        try (GtpSession session = new GtpSession(fake("hang"), Duration.ofSeconds(30))) {
            Thread.currentThread().interrupt();
            try { assertThrows(InterruptedException.class, () -> session.command("dead")); }
            finally { Thread.interrupted(); }
        }
    }

    public static class FakeGtp {
        public static void main(String[] args) throws Exception {
            BufferedReader input = new BufferedReader(new InputStreamReader(System.in));
            String line;
            while ((line = input.readLine()) != null) {
                String id = line.split(" ")[0];
                if (args[0].equals("hang")) { Thread.sleep(30_000); continue; }
                if (args[0].equals("exit")) return;
                if (args[0].equals("wrongid")) System.out.println("=999 unexpected\n");
                else if (args[0].equals("error")) System.out.println("?" + id + " error\n");
                else System.out.println("=" + id + (line.endsWith("empty") ? "\n" : " C7\nD7\n"));
                System.out.flush();
            }
        }
    }
}
