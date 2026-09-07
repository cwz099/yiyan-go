package com.yiyan.go.engine;

import com.yiyan.go.ai.AiDecision;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.GoDifficulty;
import com.yiyan.go.game.Move;
import com.yiyan.go.game.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class KataGoOpponentTest {
    @TempDir Path directory;
    private String previousData;

    @BeforeEach
    void isolateLogs() {
        previousData = System.getProperty("yiyan.dataDir");
        System.setProperty("yiyan.dataDir", directory.toString());
    }

    @AfterEach
    void restoreLogs() {
        if (previousData == null) System.clearProperty("yiyan.dataDir");
        else System.setProperty("yiyan.dataDir", previousData);
    }

    private ProcessBuilder fake() {
        return new ProcessBuilder(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "-cp", Path.of("target/test-classes").toAbsolutePath().toString(), FakeOpponentGtp.class.getName());
    }

    @Test
    void keepsOneEngineSynchronizedAcrossTurns() throws Exception {
        BoardState board = new BoardState(9);
        List<Move> history = new ArrayList<>();
        history.add(board.play(0, 0).move());

        try (KataGoOpponent opponent = new KataGoOpponent(GoDifficulty.FIVE, this::fake, "test-game")) {
            AiDecision first = opponent.chooseMove(board, history);
            assertEquals(new Point(3, 5), first.point()); // D4
            assertFalse(first.motivation().isBlank());
            history.add(board.play(first.point().x(), first.point().y()).move());
            history.add(board.play(1, 0).move());

            AiDecision second = opponent.chooseMove(board, history);
            assertEquals(new Point(4, 4), second.point()); // E5; emitted only by the same process
            assertEquals("KataGo · 五段", opponent.displayName());
        }
        String log = Files.readString(directory.resolve("logs/application.jsonl"));
        assertTrue(log.contains("\"event\":\"ranked_engine_started\""));
        assertTrue(log.contains("\"event\":\"ranked_move_completed\""));
        assertTrue(log.contains("\"syncMode\":\"incremental\""));
        assertTrue(log.contains("\"gameId\":\"test-game\""));
    }

    @Test
    void rejectsIncompleteOrInconsistentHistory() {
        BoardState board = new BoardState(9);
        Move played = board.play(0, 0).move();
        KataGoOpponent opponent = new KataGoOpponent(GoDifficulty.ONE, this::fake);
        try {
            assertThrows(EngineException.class, () -> opponent.chooseMove(board));
            assertThrows(EngineException.class, () -> opponent.chooseMove(board, List.of()));

            BoardState different = new BoardState(9);
            different.play(1, 0);
            assertThrows(EngineException.class, () -> opponent.chooseMove(different, List.of(played)));
        } finally {
            opponent.close();
        }
    }

    @Test
    void nineDifficultyBandsIncreaseSearchAndReduceRandomness() {
        GoDifficulty[] levels = GoDifficulty.values();
        assertEquals(9, levels.length);
        assertEquals("一段", levels[0].toString());
        assertEquals("九段", levels[8].toString());
        for (int i = 1; i < levels.length; i++) {
            assertTrue(levels[i].visits() > levels[i - 1].visits());
            assertTrue(levels[i].seconds() > levels[i - 1].seconds());
            assertTrue(levels[i].temperature() < levels[i - 1].temperature());
        }
        String strongest = KataGoOpponent.overrides(GoDifficulty.NINE);
        assertTrue(strongest.contains("maxVisits=4096"));
        assertTrue(strongest.contains("maxTime=10.0"));
        assertTrue(strongest.contains("chosenMoveTemperature=0.0"));
    }

    public static final class FakeOpponentGtp {
        public static void main(String[] args) throws Exception {
            int generated = 0;
            try (BufferedReader input = new BufferedReader(new InputStreamReader(System.in))) {
                String line;
                while ((line = input.readLine()) != null) {
                    int space = line.indexOf(' ');
                    String id = space < 0 ? line : line.substring(0, space);
                    String command = space < 0 ? "" : line.substring(space + 1);
                    String body = "";
                    if (command.equals("name")) body = "KataGo";
                    else if (command.startsWith("genmove ")) body = generated++ == 0 ? "D4" : "E5";
                    else if (!(command.startsWith("boardsize ") || command.equals("clear_board")
                            || command.equals("kata-set-rules chinese") || command.startsWith("komi ")
                            || command.startsWith("play "))) {
                        System.out.println("?" + id + " unknown command\n");
                        System.out.flush();
                        continue;
                    }
                    System.out.println("=" + id + (body.isEmpty() ? "" : " " + body) + "\n");
                    System.out.flush();
                }
            }
        }
    }
}
