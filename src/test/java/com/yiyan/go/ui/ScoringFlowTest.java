package com.yiyan.go.ui;

import com.yiyan.go.ai.MovePolicy;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.FinalScore;
import com.yiyan.go.game.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.awt.event.ActionEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/** End-to-end checks for shallow analysis and the user-confirmed scoring workflow. */
class ScoringFlowTest {
    private ScoringDialog dialog;
    private BoardState original;

    @AfterEach
    void disposeDialog() throws Exception {
        if (dialog != null) SwingUtilities.invokeAndWait(dialog::dispose);
    }

    @Test
    void nineteenByNineteenPromptPreparationRemainsBounded() {
        BoardState board = new BoardState(19);
        for (int turn = 0; turn < 70; turn++) {
            var legal = board.legalPoints();
            Point point = legal.get(Math.floorMod(turn * 47, legal.size()));
            assertTrue(board.play(point.x(), point.y()).success());
        }
        long start = System.nanoTime();
        assertTimeout(Duration.ofSeconds(5), () -> {
            for (Point point : board.legalPoints()) assertTrue(MovePolicy.analyze(board, point).legal());
            assertFalse(MovePolicy.candidates(board).isEmpty());
            assertFalse(MovePolicy.assessPass(board).allowed());
        });
        System.out.println("19x19 prompt analysis milliseconds: " + (System.nanoTime() - start) / 1_000_000);
    }

    @Test
    void defaultsToPendingWithoutConsentAndCannotSubmit() throws Exception {
        create();
        onEdt(() -> {
            assertFalse(acknowledged().isSelected());
            assertFalse(confirm().isEnabled());
            assertEquals(ScoringDialog.Action.PENDING, result().action());
            confirm().doClick(0);
            assertEquals(ScoringDialog.Action.PENDING, result().action());
            // Even direct action dispatch must retain the explicit consent guard.
            for (var listener : confirm().getActionListeners()) {
                listener.actionPerformed(new ActionEvent(confirm(), ActionEvent.ACTION_PERFORMED, "test"));
            }
            assertEquals(ScoringDialog.Action.PENDING, result().action());
            assertEquals(Set.of(), preview().deadStones());
        });
    }

    @Test
    void selectingOneStoneMarksAndUnmarksTheWholeConnectedGroup() throws Exception {
        create();
        String before = original.asciiDiagram();
        onEdt(() -> {
            toggle(2, 2);
            Set<Point> expected = Set.of(new Point(2, 2), new Point(3, 2), new Point(3, 3));
            assertEquals(expected, preview().deadStones());
            assertEquals(0, preview().blackStones());
            assertEquals(3, preview().whiteStones());
            assertEquals(expected, get((BoardPanel) get(dialog, "board"), "deadMarks"));
            assertEquals(before, original.asciiDiagram());
            toggle(3, 3);
            assertTrue(preview().deadStones().isEmpty());
            assertEquals(3, preview().blackStones());
            assertEquals(before, original.asciiDiagram());
        });
    }

    @Test
    void consentEnablesSubmissionAndReturnsExactlyTheReviewedScore() throws Exception {
        create();
        String before = original.asciiDiagram();
        onEdt(() -> {
            toggle(2, 2);
            FinalScore reviewed = preview();
            assertFalse(confirm().isEnabled());
            acknowledged().doClick(0);
            assertTrue(confirm().isEnabled());
            confirm().doClick(0);
            assertEquals(ScoringDialog.Action.CONFIRM, result().action());
            assertEquals(reviewed, result().score());
            assertEquals(before, original.asciiDiagram());
            assertEquals(8, original.moveNumber());
            assertTrue(original.gameOver());
        });
    }

    @Test
    void anyMarkChangeRevokesPriorConsentIncludingNeutralRegions() throws Exception {
        create();
        onEdt(() -> {
            acknowledged().doClick(0);
            assertTrue(confirm().isEnabled());
            toggle(2, 2);
            assertFalse(acknowledged().isSelected());
            assertFalse(confirm().isEnabled());
            acknowledged().doClick(0);
            ((JComboBox<?>) get(dialog, "mode")).setSelectedIndex(1);
            toggle(0, 0);
            assertFalse(preview().neutralPoints().isEmpty());
            assertFalse(acknowledged().isSelected());
            assertFalse(confirm().isEnabled());
            assertEquals(ScoringDialog.Action.PENDING, result().action());
        });
    }

    @Test
    void returnPendingDiscardsAuthorityToSaveAndPreservesOriginalBoard() throws Exception {
        create();
        String before = original.asciiDiagram();
        onEdt(() -> {
            toggle(2, 2);
            acknowledged().doClick(0);
            button(dialog, "返回待确认").doClick(0);
            assertEquals(ScoringDialog.Action.PENDING, result().action());
            assertEquals(before, original.asciiDiagram());
            assertEquals(8, original.moveNumber());
            assertTrue(original.gameOver());
        });
    }

    @Test
    void disputedPositionReturnsContinueWithoutModifyingTheGame() throws Exception {
        create();
        String before = original.asciiDiagram();
        onEdt(() -> {
            toggle(2, 2);
            button(dialog, "有争议，继续对弈").doClick(0);
            assertEquals(ScoringDialog.Action.CONTINUE, result().action());
            assertEquals(before, original.asciiDiagram());
            assertEquals(8, original.moveNumber());
        });
    }

    private void create() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Swing 流程检查需要图形环境");
        original = new BoardState(9);
        for (int[] point : new int[][]{{2, 2}, {7, 7}, {3, 2}, {7, 6}, {3, 3}, {6, 7}}) {
            assertTrue(original.play(point[0], point[1]).success());
        }
        original.pass();
        original.pass();
        onEdt(() -> {
            try {
                Constructor<ScoringDialog> constructor = ScoringDialog.class.getDeclaredConstructor(JFrame.class, BoardState.class);
                constructor.setAccessible(true);
                dialog = constructor.newInstance(null, original);
                dialog.addNotify();
                dialog.validate();
            } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
        });
    }

    private JCheckBox acknowledged() { return (JCheckBox) get(dialog, "acknowledged"); }
    private SoftButton confirm() { return (SoftButton) get(dialog, "confirm"); }
    private FinalScore preview() { return (FinalScore) get(dialog, "preview"); }
    private ScoringDialog.Result result() { return (ScoringDialog.Result) get(dialog, "result"); }

    private void toggle(int x, int y) {
        try {
            Method method = ScoringDialog.class.getDeclaredMethod("togglePoint", int.class, int.class);
            method.setAccessible(true);
            method.invoke(dialog, x, y);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static Object get(Object target, String name) {
        try {
            Field field = target.getClass().getDeclaredField(name);
            field.setAccessible(true);
            return field.get(target);
        } catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }

    private static AbstractButton button(Container container, String text) {
        for (Component component : container.getComponents()) {
            if (component instanceof AbstractButton candidate && candidate.getText().equals(text)) return candidate;
            if (component instanceof Container child) {
                AbstractButton found = button(child, text);
                if (found != null) return found;
            }
        }
        return null;
    }

    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
}
