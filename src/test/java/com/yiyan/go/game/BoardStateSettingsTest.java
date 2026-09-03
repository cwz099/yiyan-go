package com.yiyan.go.game;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BoardStateSettingsTest {
    @Test
    void customKomiIsCopiedAndUsedForScoring() {
        BoardState game = new BoardState(13, 2.5);
        assertEquals(2.5, game.komi());
        assertEquals(0, game.estimateScore().black());
        assertEquals(2.5, game.estimateScore().white());
        assertEquals(2.5, game.estimateScore().komi());
        BoardState copy = game.copy();
        assertEquals(13, copy.size());
        assertEquals(2.5, copy.komi());
        assertEquals(game.estimateScore(), copy.estimateScore());
        assertEquals(7.5, new BoardState(9).komi());
    }

    @Test
    void rejectsInvalidKomi() {
        assertThrows(IllegalArgumentException.class, () -> new BoardState(19, Double.NaN));
        assertThrows(IllegalArgumentException.class, () -> new BoardState(19, Double.POSITIVE_INFINITY));
        assertThrows(IllegalArgumentException.class, () -> new BoardState(19, -1));
        assertThrows(IllegalArgumentException.class, () -> new BoardState(19, 31));
    }

    @Test
    void resignationEndsGameAndCopyPreservesResult() {
        BoardState game = new BoardState(9);
        assertNull(game.resignedBy());
        assertTrue(game.play(4, 4).success());
        game.resign(Stone.BLACK);
        assertTrue(game.gameOver());
        assertEquals(Stone.BLACK, game.resignedBy());
        assertEquals(1, game.moveNumber(), "认输不是普通落子");
        assertFalse(game.play(0, 0).success());
        assertFalse(game.pass().success());
        assertTrue(game.legalPoints().isEmpty());
        BoardState copy = game.copy();
        assertTrue(copy.gameOver());
        assertEquals(Stone.BLACK, copy.resignedBy());
        assertEquals(game.asciiDiagram(), copy.asciiDiagram());
        game.resign(Stone.WHITE);
        assertEquals(Stone.BLACK, game.resignedBy(), "终局后不能重写认输方");
    }

    @Test
    void onlyPlayersCanResignAndPassTerminationHasNoResigner() {
        BoardState game = new BoardState(9);
        assertThrows(IllegalArgumentException.class, () -> game.resign(Stone.EMPTY));
        assertThrows(IllegalArgumentException.class, () -> game.resign(null));
        assertFalse(game.gameOver());
        game.pass();
        game.pass();
        game.resign(Stone.BLACK);
        assertNull(game.resignedBy());
    }
}
