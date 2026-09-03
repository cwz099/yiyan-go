package com.yiyan.go.ai;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;

final class MovePolicyTest {
    @Test
    void reportsActualCaptureWithoutChangingBoard() throws Exception {
        BoardState board = board(Stone.BLACK, ".....", "..B..", ".BWB.", ".....", ".....");
        MovePolicy.Assessment a = MovePolicy.analyze(board, new Point(2, 3));
        assertTrue(a.legal());
        assertEquals(1, a.captured());
        assertEquals(4, a.libertiesAfter());
        assertTrue(a.summary().contains("实际提掉 1 子"));
        assertEquals(Stone.WHITE, board.stoneAt(2, 2));
        assertEquals(0, board.moveNumber());
    }

    @Test
    void countsDistinctGroupsNotAdjacentStones() throws Exception {
        BoardState separate = board(Stone.BLACK, ".....", ".....", ".B.B.", ".....", ".....");
        assertEquals(2, MovePolicy.analyze(separate, new Point(2, 2)).connectedGroups());
        BoardState together = board(Stone.BLACK, ".....", ".BB..", ".B...", ".....", ".....");
        assertEquals(1, MovePolicy.analyze(together, new Point(2, 2)).connectedGroups());
    }

    @Test
    void identifiesRescueAndDoesNotPassOverIt() throws Exception {
        BoardState board = board(Stone.BLACK, ".W...", "WBW..", ".....", ".....", ".....");
        assertTrue(board.pass().success());
        assertTrue(board.pass().success());
        // A fixture for a midgame offer to stop, retaining the same color and tactical position.
        set(board, "gameOver", false);
        set(board, "consecutivePasses", 1);
        set(board, "moveNumber", 40);
        MovePolicy.Assessment a = MovePolicy.analyze(board, new Point(1, 2));
        assertEquals(1, a.rescuedGroups());
        assertEquals(3, a.libertiesAfter());
        MovePolicy.PassAssessment pass = MovePolicy.assessPass(board);
        assertFalse(pass.allowed());
        assertTrue(pass.urgentMoves() > 0);
    }

    @Test
    void avoidsOwnEyeAndNonCapturingSelfAtari() throws Exception {
        BoardState eye = board(Stone.BLACK, ".....", ".BBB.", ".B.B.", ".BBB.", ".....");
        MovePolicy.Assessment a = MovePolicy.analyze(eye, new Point(2, 2));
        assertTrue(a.legal());
        assertTrue(a.fillsOwnEye());
        assertFalse(a.meaningful());
        assertFalse(MovePolicy.candidates(eye).stream().anyMatch(c -> c.point().equals(new Point(2, 2))));
        BoardState atari = board(Stone.BLACK, ".W...", "W.W..", ".....", ".....", ".....");
        MovePolicy.Assessment b = MovePolicy.analyze(atari, new Point(1, 1));
        assertTrue(b.selfAtari());
        assertFalse(b.meaningful());
    }

    @Test
    void distinguishesCornerEyeAndFalseEye() throws Exception {
        BoardState eye = board(Stone.BLACK, ".B...", "BB...", ".....", ".....", ".....");
        assertTrue(MovePolicy.analyze(eye, new Point(0, 0)).fillsOwnEye());
        BoardState falseEye = board(Stone.BLACK, ".B...", "BW...", ".....", ".....", ".....");
        assertFalse(MovePolicy.analyze(falseEye, new Point(0, 0)).fillsOwnEye());
    }

    @Test
    void rejectsOpeningPassButAcceptsMidgameOfferWithoutImmediateTasks() throws Exception {
        BoardState empty = new BoardState(9);
        assertFalse(MovePolicy.assessPass(empty).allowed());
        empty.pass();
        assertFalse(MovePolicy.assessPass(empty).allowed());
        BoardState mid = board(Stone.BLACK,
                "B.B.B.B.B", ".........", ".........", ".........", ".........",
                ".........", ".........", ".........", ".W.W.W.W.");
        mid.pass();
        assertTrue(MovePolicy.assessPass(mid).allowed());
        assertEquals(0, MovePolicy.assessPass(mid).urgentMoves());
    }

    @Test
    void passesInsteadOfFillingTwoEyes() throws Exception {
        BoardState board = board(Stone.BLACK, "BBBBB", "B.BBB", "BBBBB", "BBB.B", "BBBBB");
        assertTrue(MovePolicy.candidates(board).isEmpty());
        assertTrue(MovePolicy.assessPass(board).allowed());
        assertTrue(LocalGoOpponent.chooseWithoutDelay(board).pass());
    }

    @Test
    void modelExplanationDoesNotRepeatUnverifiedVictoryClaims() {
        String text = MovePolicy.explainModelChoice(new BoardState(9), new Point(2, 2),
                "白棋优势明显，必胜，棋盘已满，停一手即可巩固胜果。");
        assertTrue(text.startsWith("落在 C7"));
        assertTrue(text.contains("4 口气"));
        assertFalse(text.contains("必胜"));
        assertFalse(text.contains("优势明显"));
        assertFalse(text.contains("棋盘已满"));
    }

    @Test
    void tacticalIntentMustMatchFactsAndStrategicIntentRemainsAttributed() throws Exception {
        BoardState empty = new BoardState(9);
        String unsupported = MovePolicy.explainModelChoice(empty, new Point(2, 2), "capture", "已经提掉10子");
        assertFalse(unsupported.contains("提掉"));
        assertFalse(unsupported.contains("提掉10子"));
        String develop = MovePolicy.explainModelChoice(empty, new Point(2, 2), "develop", "必胜");
        assertTrue(develop.contains("尝试向外发展"));
        assertFalse(develop.contains("已判断死活"));
        assertTrue(develop.length() < 60);
        BoardState capture = board(Stone.BLACK, ".....", "..B..", ".BWB.", ".....", ".....");
        assertTrue(MovePolicy.explainModelChoice(capture, new Point(2, 3), "capture", "")
                .contains("提掉 1 子"));
    }

    @Test
    void localNineByNineGameNaturallyReachesTwoPasses() {
        assertTimeoutPreemptively(Duration.ofSeconds(30), () -> {
            BoardState board = new BoardState(9);
            while (!board.gameOver() && board.moveNumber() < 324) {
                AiDecision decision = LocalGoOpponent.chooseWithoutDelay(board);
                if (decision.pass()) assertTrue(board.pass().success());
                else {
                    MovePolicy.Assessment a = MovePolicy.analyze(board, decision.point());
                    assertTrue(a.meaningful());
                    assertTrue(board.play(decision.point().x(), decision.point().y()).success());
                }
            }
            assertTrue(board.gameOver(), "local game did not settle: " + board.moveNumber() + "\n" + board.asciiDiagram());
            assertEquals(2, board.consecutivePasses());
        });
    }

    private static BoardState board(Stone turn, String... rows) throws Exception {
        BoardState board = new BoardState(rows.length);
        Stone[][] stones = new Stone[rows.length][rows.length];
        for (int y = 0; y < rows.length; y++) {
            assertEquals(rows.length, rows[y].length());
            for (int x = 0; x < rows.length; x++) {
                stones[x][y] = switch (rows[y].charAt(x)) {
                    case 'B' -> Stone.BLACK;
                    case 'W' -> Stone.WHITE;
                    default -> Stone.EMPTY;
                };
            }
        }
        set(board, "board", stones);
        set(board, "turn", turn);
        return board;
    }

    private static void set(BoardState board, String name, Object value) throws Exception {
        Field field = BoardState.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(board, value);
    }
}
