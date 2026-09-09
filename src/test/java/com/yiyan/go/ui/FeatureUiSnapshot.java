package com.yiyan.go.ui;

import com.yiyan.go.diagnostics.AppLogs;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.GameSettings;
import com.yiyan.go.game.GoDifficulty;
import com.yiyan.go.game.Stone;
import com.yiyan.go.recording.GameRecorder;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/** Manual visual smoke check; uses isolated -Dyiyan.dataDir and no network access. */
public final class FeatureUiSnapshot {
    public static void main(String[] args) throws Exception {
        Path output = Path.of(args.length == 0 ? "target/feature-preview" : args[0]).toAbsolutePath();
        Files.createDirectories(output);
        Path data = output.resolve("test-data");
        System.setProperty("yiyan.dataDir", data.toString());
        GameSettings settings = new GameSettings(13, 7.5, Stone.BLACK,
                true, true, true, GoDifficulty.SIX);
        settings.save(data.resolve("settings.xml"));
        SwingUtilities.invokeAndWait(() -> {
            Theme.installDefaults();
            MainFrame owner = new MainFrame();
            try {
                owner.setSize(1420, 880);
                owner.addNotify();
                owner.validate();
                capture(owner.getContentPane(), output.resolve("main.png"));
                BoardState board = new BoardState(13, 7.5);
                GameRecorder recorder = GameRecorder.start(board, Stone.BLACK, "本地陪练 · 视觉测试");
                int[][] points = {{3, 3}, {9, 9}, {9, 3}, {3, 9}, {6, 3}, {4, 9}};
                for (int[] point : points) {
                    var result = board.play(point[0], point[1]);
                    recorder.record(board, result, result.move().stone() == Stone.BLACK ? "human" : "本地陪练",
                            result.move().stone() == Stone.WHITE ? "视觉测试说明：照顾这片棋的联络。" : "", 120);
                }
                recorder.pause(board);
                if (!recorder.error().isBlank()) throw new IllegalStateException(recorder.error());
                AppLogs.event("network", "visual_test_only", Map.of("source", "mock", "httpStatus", 200,
                        "durationMs", 120, "action", "PLAY", "gameId", recorder.id()));
                captureDialog(output.resolve("settings.png"), () -> GameSettingsDialog.showDialog(owner, settings));
                captureDialog(output.resolve("replay.png"), () -> ReplayDialog.showDialog(owner), "整盘分析");
                captureDialog(output.resolve("logs.png"), () -> LogsDialog.showDialog(owner));
                board.pass();
                board.pass();
                captureDialog(output.resolve("scoring.png"), () -> ScoringDialog.showDialog(owner, board));
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            } finally {
                owner.dispose();
            }
        });
        System.out.println("Feature snapshots: " + output);
    }

    private static void captureDialog(Path target, Runnable open) throws Exception {
        captureDialog(target, open, null);
    }

    private static void captureDialog(Path target, Runnable open, String tabTitle) throws Exception {
        AtomicReference<Exception> failure = new AtomicReference<>();
        Timer timer = new Timer(1200, event -> {
            for (Window window : Window.getWindows()) {
                if (window instanceof JDialog dialog && dialog.isShowing()) {
                    try {
                        if (tabTitle != null) selectTab(dialog, tabTitle);
                        capture(dialog.getContentPane(), target);
                    }
                    catch (Exception exception) { failure.set(exception); }
                    dialog.dispose();
                    break;
                }
            }
        });
        timer.setRepeats(false);
        timer.start();
        try { open.run(); } finally { timer.stop(); }
        if (failure.get() != null) throw failure.get();
        if (!Files.exists(target)) throw new IllegalStateException("Dialog snapshot missing: " + target);
    }

    private static boolean selectTab(Container parent, String title) {
        for (Component component : parent.getComponents()) {
            if (component instanceof JTabbedPane tabs) {
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    if (title.equals(tabs.getTitleAt(i))) { tabs.setSelectedIndex(i); return true; }
                }
            }
            if (component instanceof Container child && selectTab(child, title)) return true;
        }
        return false;
    }

    private static void capture(Container component, Path target) throws Exception {
        component.doLayout();
        BufferedImage bitmap = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = bitmap.createGraphics();
        component.printAll(graphics);
        graphics.dispose();
        ImageIO.write(bitmap, "png", target.toFile());
    }
}
