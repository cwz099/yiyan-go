package com.yiyan.go.ui;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.Point;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.event.ActionEvent;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class BoardPanelTest {
    @Test
    void smallerBoardsUseFourCornerStarsAndCenter() {
        assertEquals(9, BoardPanel.starPoints(19).size());
        assertEquals(5, BoardPanel.starPoints(13).size());
        assertTrue(BoardPanel.starPoints(13).contains(new Point(3, 3)));
        assertTrue(BoardPanel.starPoints(13).contains(new Point(6, 6)));
        assertEquals(5, BoardPanel.starPoints(9).size());
        assertTrue(BoardPanel.starPoints(9).contains(new Point(2, 6)));
        assertTrue(BoardPanel.starPoints(9).contains(new Point(4, 4)));
    }

    @Test
    void sizeChangesResetKeyboardFocusWithinBoardAndAccessibility() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<BoardState> state = new AtomicReference<>(new BoardState(19));
            AtomicReference<Point> played = new AtomicReference<>();
            BoardPanel panel = new BoardPanel(state::get, () -> true,
                    (x, y) -> played.set(new Point(x, y)));
            for (int index = 0; index < 30; index++) {
                panel.getActionMap().get("move-right").actionPerformed(new ActionEvent(panel, 0, ""));
                panel.getActionMap().get("move-down").actionPerformed(new ActionEvent(panel, 0, ""));
            }
            state.set(new BoardState(9));
            panel.getActionMap().get("play").actionPerformed(new ActionEvent(panel, 0, ""));
            assertEquals(new Point(4, 4), played.get());
            assertEquals("9 路围棋棋盘", panel.getAccessibleContext().getAccessibleName());
            state.set(new BoardState(13));
            panel.getActionMap().get("play").actionPerformed(new ActionEvent(panel, 0, ""));
            assertEquals(new Point(6, 6), played.get());
            assertEquals("13 路围棋棋盘", panel.getAccessibleContext().getAccessibleName());
        });
    }

    @Test
    void replayBoardDoesNotSubmitKeyboardMoves() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            AtomicReference<Point> played = new AtomicReference<>();
            BoardPanel panel = new BoardPanel(() -> new BoardState(9), () -> false,
                    (x, y) -> played.set(new Point(x, y)));
            panel.getActionMap().get("play").actionPerformed(new ActionEvent(panel, 0, ""));
            assertNull(played.get());
        });
    }
}
