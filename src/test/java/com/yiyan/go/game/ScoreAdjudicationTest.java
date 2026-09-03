package com.yiyan.go.game;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

final class ScoreAdjudicationTest {
    @Test
    void emptyBoardsHaveOnlyNeutralPointsAndKomi() {
        for (int size : new int[]{5, 9, 13, 19}) {
            FinalScore score = ScoreAdjudication.calculate(new BoardState(size, 7.5), Set.of(), Set.of());
            assertEquals(0, score.blackTotal());
            assertEquals(7.5, score.whiteTotal());
            assertEquals(size * size, score.neutralCount());
            assertEquals(Stone.WHITE, score.winner());
            assertEquals(7.5, score.margin());
            assertTrue(score.deadStones().isEmpty());
            assertTrue(score.neutralPoints().isEmpty(), "自动中立点与用户明确标记应区分");
            assertTrue(score.summary().contains("白棋胜 7.5 点"));
        }
    }

    @Test
    void zeroKomiOnEmptyBoardIsDraw() {
        FinalScore score = ScoreAdjudication.calculate(new BoardState(9, 0), Set.of(), Set.of());
        assertEquals(Stone.EMPTY, score.winner());
        assertEquals(0, score.margin());
        assertTrue(score.summary().contains("和棋"));
    }

    @Test
    void singleColorEnclosureIsTerritoryWhileMixedBordersAreNeutral() {
        BoardState board = cornerTerritory();
        FinalScore score = ScoreAdjudication.calculate(board, Set.of(), Set.of());
        assertEquals(2, score.blackStones());
        assertEquals(1, score.whiteStones());
        assertEquals(1, score.blackTerritory());
        assertEquals(0, score.whiteTerritory());
        assertEquals(21, score.neutralCount());
        assertEquals(3, score.blackTotal());
        assertEquals(1, score.whiteTotal());
        assertEquals(Stone.BLACK, score.winner());
        assertEquals(2, score.margin());
    }

    @Test
    void komiCanChangeWinnerWithoutChangingArea() {
        BoardState board = new BoardState(5, 7.5);
        play(board, 0, 1);
        play(board, 4, 4);
        play(board, 1, 0);
        FinalScore score = ScoreAdjudication.calculate(board, Set.of(), Set.of());
        assertEquals(3, score.blackTotal());
        assertEquals(8.5, score.whiteTotal());
        assertEquals(Stone.WHITE, score.winner());
        assertEquals(5.5, score.margin());
    }

    @Test
    void removingWholeDeadGroupRecountsAreaWithoutChangingBoard() {
        BoardState board = twoStoneWhiteGroup();
        String original = board.asciiDiagram();
        int moves = board.moveNumber();
        Stone turn = board.turn();
        Set<Point> dead = Set.of(new Point(3, 3), new Point(3, 4));
        FinalScore score = ScoreAdjudication.calculate(board, dead, Set.of());
        assertEquals(1, score.blackStones());
        assertEquals(0, score.whiteStones());
        assertEquals(24, score.blackTerritory());
        assertEquals(0, score.neutralCount());
        assertEquals(25, score.blackTotal());
        assertEquals(dead, score.deadStones());
        assertEquals(original, board.asciiDiagram());
        assertEquals(moves, board.moveNumber());
        assertEquals(turn, board.turn());
        assertFalse(board.gameOver());
        assertEquals(Stone.WHITE, board.stoneAt(3, 3));
    }

    @Test
    void partialGroupAndEmptyOrOutsideDeadMarksAreRejected() {
        BoardState board = twoStoneWhiteGroup();
        assertThrows(IllegalArgumentException.class,
                () -> ScoreAdjudication.calculate(board, Set.of(new Point(3, 3)), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ScoreAdjudication.calculate(board, Set.of(new Point(0, 0)), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ScoreAdjudication.calculate(board, Set.of(new Point(-1, 0)), Set.of()));
        assertThrows(IllegalArgumentException.class,
                () -> ScoreAdjudication.calculate(board, Set.of(new Point(5, 0)), Set.of()));
    }

    @Test
    void neutralSeedOverridesOnlyItsCompleteEmptyRegion() {
        BoardState board = cornerTerritory();
        FinalScore corner = ScoreAdjudication.calculate(board, Set.of(), Set.of(new Point(0, 0)));
        assertEquals(0, corner.blackTerritory());
        assertEquals(22, corner.neutralCount());
        assertEquals(Set.of(new Point(0, 0)), corner.neutralPoints());

        FinalScore shared = ScoreAdjudication.calculate(board, Set.of(), Set.of(new Point(2, 2)));
        assertEquals(21, shared.neutralPoints().size());
        assertFalse(shared.neutralPoints().contains(new Point(0, 0)));
        assertEquals(1, shared.blackTerritory());
        assertEquals(ScoreAdjudication.emptyRegionAt(board, Set.of(), new Point(2, 2)), shared.neutralPoints());
    }

    @Test
    void removedDeadPointCanBeNeutralSeedButLivingStoneCannot() {
        BoardState board = twoStoneWhiteGroup();
        Set<Point> dead = Set.of(new Point(3, 3), new Point(3, 4));
        FinalScore score = ScoreAdjudication.calculate(board, dead, Set.of(new Point(3, 3)));
        assertEquals(24, score.neutralCount());
        assertEquals(24, score.neutralPoints().size());
        assertEquals(0, score.blackTerritory());
        assertTrue(score.neutralPoints().containsAll(dead));
        assertThrows(IllegalArgumentException.class,
                () -> ScoreAdjudication.calculate(board, dead, Set.of(new Point(1, 1))));
        assertThrows(IllegalArgumentException.class,
                () -> ScoreAdjudication.calculate(board, dead, Set.of(new Point(1, 5))));
    }

    @Test
    void groupTraversalIsOrthogonalAndDoesNotConnectDiagonalStones() {
        BoardState board = new BoardState(5, 0);
        play(board, 0, 0);
        play(board, 4, 4);
        play(board, 1, 1);
        assertEquals(Set.of(new Point(0, 0)), ScoreAdjudication.groupAt(board, new Point(0, 0)));
        assertTrue(ScoreAdjudication.groupAt(board, new Point(2, 2)).isEmpty());
        assertTrue(ScoreAdjudication.emptyRegionAt(board, Set.of(), new Point(0, 0)).isEmpty());
        assertThrows(IllegalArgumentException.class,
                () -> ScoreAdjudication.groupAt(board, new Point(5, 0)));
    }

    @Test
    void returnedMarksAreDefensiveImmutableCopies() {
        BoardState board = twoStoneWhiteGroup();
        Set<Point> dead = new HashSet<>(Set.of(new Point(3, 3), new Point(3, 4)));
        Set<Point> neutral = new HashSet<>(Set.of(new Point(0, 0)));
        FinalScore score = ScoreAdjudication.calculate(board, dead, neutral);
        dead.clear();
        neutral.clear();
        assertEquals(2, score.deadStones().size());
        assertEquals(24, score.neutralPoints().size());
        assertThrows(UnsupportedOperationException.class, () -> score.deadStones().clear());
        assertThrows(UnsupportedOperationException.class, () -> score.neutralPoints().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ScoreAdjudication.groupAt(board, new Point(3, 3)).clear());
        assertThrows(UnsupportedOperationException.class,
                () -> ScoreAdjudication.emptyRegionAt(board, Set.of(), new Point(0, 0)).clear());
    }

    @Test
    void capturedStonesAreNotAddedAgainToChineseAreaScore() {
        BoardState board = new BoardState(5, 0);
        play(board, 1, 1); play(board, 0, 1);
        play(board, 4, 4); play(board, 1, 0);
        play(board, 4, 3); play(board, 2, 1);
        play(board, 3, 4); play(board, 1, 2);
        assertEquals(1, board.whiteCaptures());
        FinalScore score = ScoreAdjudication.calculate(board, Set.of(), Set.of());
        assertEquals(3, score.blackStones());
        assertEquals(4, score.whiteStones());
        assertEquals(2, score.whiteTerritory()); // The captured point and the upper-left corner.
        assertEquals(6, score.whiteTotal());
        assertEquals(1, board.whiteCaptures());
    }

    @Test
    void finalScoreRejectsInvalidTotalsAndCoordinates() {
        assertThrows(IllegalArgumentException.class,
                () -> new FinalScore(5, Set.of(), Set.of(), 1, 0, 0, 0, 25, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FinalScore(5, Set.of(), Set.of(), 0, 0, 0, 0, 25, Double.NaN));
        assertThrows(IllegalArgumentException.class,
                () -> new FinalScore(5, Set.of(new Point(5, 0)), Set.of(), 0, 0, 0, 0, 25, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new FinalScore(5, Set.of(), Set.of(new Point(0, 0)), 25, 0, 0, 0, 0, 0));
    }

    private static BoardState cornerTerritory() {
        BoardState board = new BoardState(5, 0);
        play(board, 0, 1); play(board, 4, 4); play(board, 1, 0);
        return board;
    }

    private static BoardState twoStoneWhiteGroup() {
        BoardState board = new BoardState(5, 0);
        play(board, 1, 1); play(board, 3, 3);
        assertTrue(board.pass().success());
        play(board, 3, 4);
        return board;
    }

    private static void play(BoardState board, int x, int y) {
        MoveResult move = board.play(x, y);
        assertTrue(move.success(), move.message());
    }
}
