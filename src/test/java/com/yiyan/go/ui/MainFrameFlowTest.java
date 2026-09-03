package com.yiyan.go.ui;

import com.yiyan.go.ai.AiDecision;
import com.yiyan.go.ai.GoOpponent;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.GameSettings;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;
import com.yiyan.go.game.FinalScore;
import com.yiyan.go.game.ScoreAdjudication;
import com.yiyan.go.recording.GameRecorder;
import com.yiyan.go.recording.GameArchive;
import com.yiyan.go.recording.GameRecord;
import com.yiyan.go.engine.EngineVerdict;
import com.yiyan.go.engine.ScoreEngine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.SwingUtilities;
import java.awt.GraphicsEnvironment;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

class MainFrameFlowTest {
    @TempDir Path directory;
    private String previousDirectory;
    private MainFrame frame;

    @BeforeEach void isolateUserData() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "Swing 流程检查需要图形环境");
        previousDirectory = System.getProperty("yiyan.dataDir");
        System.setProperty("yiyan.dataDir", directory.toString());
    }

    @AfterEach void closeWindow() throws Exception {
        if (frame != null) SwingUtilities.invokeAndWait(frame::dispose);
        SwingUtilities.invokeAndWait(() -> { });
        if (previousDirectory == null) System.clearProperty("yiyan.dataDir");
        else System.setProperty("yiyan.dataDir", previousDirectory);
    }

    private void create(GameSettings settings, GoOpponent opponent) throws Exception {
        settings.save(directory.resolve("settings.xml"));
        onEdt(() -> {
            frame = new MainFrame();
            set("opponent", opponent);
            frame.addNotify();
            frame.validate();
        });
    }

    @Test void whitePlayerReceivesBlackOpeningAndUndoReturnsToWhiteTurn() throws Exception {
        create(new GameSettings(9, 5.5, Stone.WHITE, true, false), choosingFirstLegal());
        await(() -> board().moveNumber() == 1 && !thinking());
        onEdt(() -> {
            assertEquals(Stone.WHITE, board().turn());
            Point point = board().legalPoints().get(0);
            invoke("handleHumanPlay", new Class<?>[]{int.class, int.class}, point.x(), point.y());
        });
        await(() -> board().moveNumber() == 3 && !thinking());
        onEdt(() -> {
            invoke("undoTurn");
            assertEquals(1, board().moveNumber());
            assertEquals(Stone.WHITE, board().turn());
            assertEquals(5.5, board().komi());
            assertEquals("", ((GameRecorder) get("recorder")).error());
        });
    }

    @Test void consecutivePassesAwaitConfirmationThenScoreDrawAndUndoClearsResult() throws Exception {
        create(new GameSettings(9, 0, Stone.BLACK, true, false), new GoOpponent() {
            public AiDecision chooseMove(BoardState board) { return AiDecision.pass("测试停一手"); }
            public String displayName() { return "离线测试对手"; }
        });
        onEdt(() -> invoke("handleHumanPass"));
        await(() -> board().gameOver() && !thinking());
        onEdt(() -> {
            assertTrue(((String) get("resultSummary")).contains("等待结算"));
            assertTrue(((SoftButton) get("scoringButton")).isVisible());
            FinalScore score = ScoreAdjudication.calculate(board(), java.util.Set.of(), java.util.Set.of());
            invoke("confirmFinalScore", new Class<?>[]{FinalScore.class}, score);
            assertTrue(((String) get("resultSummary")).contains("和棋"));
            invoke("undoTurn");
            assertFalse(board().gameOver());
            assertEquals(0, board().moveNumber());
            assertEquals("", get("resultSummary"));
            assertEquals("", ((GameRecorder) get("recorder")).error());
        });
    }

    @Test void disputedScoringCanResumeEvenWhenUndoIsDisabled() throws Exception {
        create(new GameSettings(9, 7.5, Stone.BLACK, false, false), new GoOpponent() {
            public AiDecision chooseMove(BoardState board) { return AiDecision.pass("测试停手"); }
            public String displayName() { return "离线测试对手"; }
        });
        onEdt(() -> invoke("handleHumanPass"));
        await(() -> board().gameOver() && !thinking());
        onEdt(() -> {
            invoke("resumeAfterPasses");
            assertEquals(0, board().moveNumber());
            assertFalse(board().gameOver());
            assertEquals(Stone.BLACK, board().turn());
            assertEquals("", ((GameRecorder) get("recorder")).error());
            assertEquals("", get("resultSummary"));
        });
    }

    @Test void disabledFallbackPausesAndRetryContinuesSameGame() throws Exception {
        create(new GameSettings(9, 7.5, Stone.BLACK, true, false), failing());
        onEdt(() -> invoke("handleHumanPlay", new Class<?>[]{int.class, int.class}, 4, 4));
        await(() -> !thinking() && ((SoftButton) get("retryButton")).isVisible());
        onEdt(() -> {
            assertEquals(1, board().moveNumber());
            assertEquals(Stone.WHITE, board().turn());
            set("opponent", choosingFirstLegal());
            invoke("startAiTurn", new Class<?>[]{boolean.class}, false);
        });
        await(() -> board().moveNumber() == 2 && !thinking());
        onEdt(() -> assertEquals("", ((GameRecorder) get("recorder")).error()));
    }

    @Test void enabledFallbackShowsRealSource() throws Exception {
        create(new GameSettings(9, 7.5, Stone.BLACK, true, true), failing());
        onEdt(() -> invoke("handleHumanPlay", new Class<?>[]{int.class, int.class}, 4, 4));
        await(() -> board().moveNumber() == 2 && !thinking());
        onEdt(() -> {
            assertEquals("本手本地接续", get("lastAiStatus"));
            assertTrue(get("narratives").toString().contains("本地接续"));
            assertEquals("", ((GameRecorder) get("recorder")).error());
        });
    }

    @Test void newGameRejectsStaleAiResponse() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        GoOpponent slow = new GoOpponent() {
            public AiDecision chooseMove(BoardState board) throws InterruptedException {
                started.countDown();
                release.await(3, TimeUnit.SECONDS);
                return AiDecision.play(new Point(0, 0), "旧局结果");
            }
            public String displayName() { return "慢速测试对手"; }
        };
        create(new GameSettings(9, 7.5, Stone.BLACK, true, false), slow);
        onEdt(() -> invoke("handleHumanPlay", new Class<?>[]{int.class, int.class}, 4, 4));
        assertTrue(started.await(3, TimeUnit.SECONDS));
        onEdt(() -> invoke("beginNewGame"));
        release.countDown();
        Thread.sleep(150);
        onEdt(() -> {
            assertEquals(0, board().moveNumber());
            assertFalse(thinking());
            assertEquals("", ((GameRecorder) get("recorder")).error());
        });
    }

    @Test void undoResignationRemovesSamePlyOutcome() throws Exception {
        create(new GameSettings(9, 7.5, Stone.BLACK, true, false), choosingFirstLegal());
        onEdt(() -> {
            @SuppressWarnings("unchecked") var stack = (java.util.Deque<BoardState>) get("undoStack");
            stack.addLast(board().copy());
            board().resign(Stone.BLACK);
            invoke("finishGame");
            assertTrue(get("narratives").toString().contains("FINISH"));
            invoke("undoTurn");
            assertFalse(get("narratives").toString().contains("FINISH"));
            assertFalse(board().gameOver());
            assertEquals("", ((GameRecorder) get("recorder")).error());
        });
    }

    @Test void engineAutomaticallyScoresAndPersistsItsRealSource() throws Exception {
        create(new GameSettings(9, 7.5, Stone.BLACK, true, false), passing());
        onEdt(() -> set("scoreEngine", (ScoreEngine) (position, moves) -> {
            assertEquals(position.moveNumber(), moves.size());
            return verdict(position, true);
        }));
        onEdt(() -> invoke("handleHumanPlay", new Class<?>[]{int.class, int.class}, 2, 2));
        await(() -> board().moveNumber() == 2 && !thinking());
        onEdt(() -> invoke("handleHumanPass"));
        await(() -> get("confirmedScore") != null);
        onEdt(() -> {
            assertTrue(get("resultSummary").toString().contains("KataGo 判定"));
            assertTrue(((SoftButton) get("reviewScoreButton")).isEnabled());
            String id = ((GameRecorder) get("recorder")).id();
            try {
                var saved = GameArchive.load(directory.resolve("games").resolve(id + ".xml"));
                assertEquals(GameRecord.Status.SCORED, saved.status());
                assertTrue(saved.result().contains("KataGo test · fixture"));
                assertTrue(GameArchive.toSgf(saved).contains("RE[B+73.5]"));
            } catch (IOException e) { throw new AssertionError(e); }
            invoke("undoTurn");
            assertNull(get("confirmedScore"));
            assertNull(get("engineVerdict"));
        });
    }

    @Test void disagreeingEngineNeverSilentlyDeclaresAWinner() throws Exception {
        create(new GameSettings(9, 7.5, Stone.BLACK, true, false), passing());
        onEdt(() -> {
            set("scoreEngine", (ScoreEngine) (position, moves) -> verdict(position, false));
            invoke("handleHumanPass");
        });
        await(() -> get("engineVerdict") != null && !(boolean) get("scoring"));
        onEdt(() -> {
            assertNull(get("confirmedScore"));
            assertTrue(get("resultSummary").toString().contains("尚需收官"));
            assertTrue(((SoftButton) get("scoringButton")).isEnabled());
            invoke("resumeAfterPasses");
            assertFalse(board().gameOver());
            assertNull(get("engineVerdict"));
        });
    }

    @Test void failedEngineLeavesRetryAndManualScoringAvailable() throws Exception {
        create(new GameSettings(9, 7.5, Stone.BLACK, true, false), passing());
        onEdt(() -> {
            set("scoreEngine", (ScoreEngine) (position, moves) -> { throw new IOException("test engine error"); });
            invoke("handleHumanPass");
        });
        await(() -> get("resultSummary").toString().contains("可重试") && !(boolean) get("scoring"));
        onEdt(() -> {
            assertNull(get("confirmedScore"));
            assertTrue(((SoftButton) get("scoringButton")).isEnabled());
            assertTrue(((SoftButton) get("reviewScoreButton")).isEnabled());
        });
    }

    @Test void startingNewGameCancelsPendingEngineVerdict() throws Exception {
        CountDownLatch started = new CountDownLatch(1), release = new CountDownLatch(1);
        create(new GameSettings(9, 7.5, Stone.BLACK, true, false), passing());
        onEdt(() -> {
            set("scoreEngine", (ScoreEngine) (position, moves) -> {
                started.countDown();
                release.await(3, TimeUnit.SECONDS);
                return verdict(position, true);
            });
            invoke("handleHumanPass");
        });
        assertTrue(started.await(3, TimeUnit.SECONDS));
        onEdt(() -> invoke("beginNewGame"));
        release.countDown();
        Thread.sleep(150);
        onEdt(() -> {
            assertEquals(0, board().moveNumber());
            assertNull(get("confirmedScore"));
            assertFalse((boolean) get("scoring"));
            assertFalse(get("narratives").toString().contains("KataGo 判定"));
        });
    }

    private static EngineVerdict verdict(BoardState board, boolean consistent) {
        return new EngineVerdict("KataGo test", "fixture", "B+73.5",
                ScoreAdjudication.calculate(board, java.util.Set.of(), java.util.Set.of()), 10, consistent);
    }

    private GoOpponent passing() {
        return new GoOpponent() {
            public AiDecision chooseMove(BoardState board) { return AiDecision.pass("测试停手"); }
            public String displayName() { return "离线测试对手"; }
        };
    }

    private GoOpponent choosingFirstLegal() {
        return new GoOpponent() {
            public AiDecision chooseMove(BoardState board) { return AiDecision.play(board.legalPoints().get(0), "测试着手"); }
            public String displayName() { return "离线测试对手"; }
        };
    }

    private GoOpponent failing() {
        return new GoOpponent() {
            public AiDecision chooseMove(BoardState board) throws IOException { throw new IOException("test failure"); }
            public String displayName() { return "离线模拟失败"; }
        };
    }

    private BoardState board() { return (BoardState) get("state"); }
    private boolean thinking() { return (boolean) get("aiThinking"); }
    private Object get(String name) {
        try { Field field = MainFrame.class.getDeclaredField(name); field.setAccessible(true); return field.get(frame); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private void set(String name, Object value) {
        try { Field field = MainFrame.class.getDeclaredField(name); field.setAccessible(true); field.set(frame, value); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private void invoke(String name) { invoke(name, new Class<?>[0]); }
    private void invoke(String name, Class<?>[] types, Object... values) {
        try { Method method = MainFrame.class.getDeclaredMethod(name, types); method.setAccessible(true); method.invoke(frame, values); }
        catch (ReflectiveOperationException exception) { throw new AssertionError(exception); }
    }
    private static void onEdt(Runnable action) throws Exception { SwingUtilities.invokeAndWait(action); }
    private static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        AtomicBoolean success = new AtomicBoolean();
        while (System.nanoTime() < deadline) {
            onEdt(() -> success.set(condition.getAsBoolean()));
            if (success.get()) return;
            Thread.sleep(20);
        }
        fail("AI 流程没有在 6 秒内完成");
    }
}
