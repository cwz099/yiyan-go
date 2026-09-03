package com.yiyan.go.ui;

import com.yiyan.go.ai.MovePolicy;
import com.yiyan.go.engine.*;
import com.yiyan.go.game.*;
import com.yiyan.go.recording.*;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.List;

/** Offscreen component-rendering test, isolated from the user's game directory. */
public final class EngineUiSnapshot {
    public static void main(String[] args) throws Exception {
        GameRecord game = GameArchive.load(Path.of(args[0]));
        EngineVerdict verdict = new KataGoEngine(KataGoConfig.discover()).evaluate(game.finalPosition(),
                game.moves().stream().map(GameRecord.Ply::move).toList());
        Path output = Path.of("target/engine-preview").toAbsolutePath();
        Files.createDirectories(output);
        System.setProperty("yiyan.dataDir", output.resolve("data").toString());
        new GameSettings(game.size(), game.komi(), Stone.BLACK, true, false).save(output.resolve("data/settings.xml"));
        SwingUtilities.invokeAndWait(() -> {
            Theme.installDefaults();
            MainFrame frame = new MainFrame();
            try {
                frame.useBundledEngine();
                BoardState position = new BoardState(game.size(), game.komi());
                GameRecorder recorder = (GameRecorder) field(frame, "recorder");
                @SuppressWarnings("unchecked") List<Object> messages = (List<Object>) field(frame, "narratives");
                messages.clear();
                for (GameRecord.Ply ply : game.moves()) {
                    Move move = ply.move();
                    String text = move.pass() ? "暂不落子，等待对方应手。"
                            : MovePolicy.explainModelChoice(position, move.point(), "", "");
                    MoveResult result = move.pass() ? position.pass() : position.play(move.point().x(), move.point().y());
                    recorder.record(position, result, ply.source(), text, ply.elapsedMs());
                    if (move.number() >= game.moves().size() - 8) {
                        messages.add(narrative(move.number(), move.stone() == Stone.BLACK ? "PLAYER" : "AI",
                                (move.stone() == Stone.BLACK ? "你" : "白棋") + " · 第 " + move.number()
                                        + " 手 · " + move.coordinate(game.size()), move.stone() == Stone.BLACK ? "" : text));
                    }
                }
                set(frame, "state", position);
                set(frame, "engineVerdict", verdict);
                frame.addNotify();
                Method confirm = MainFrame.class.getDeclaredMethod("confirmFinalScore", FinalScore.class, String.class);
                confirm.setAccessible(true);
                confirm.invoke(frame, verdict.score(), verdict.source());
                for (int width : new int[]{1420, 1060}) {
                    frame.setSize(width, 880);
                    frame.validate();
                    layout(frame.getContentPane());
                    JScrollPane scroll = (JScrollPane) field(frame, "messageScroll");
                    scroll.getVerticalScrollBar().setValue(scroll.getVerticalScrollBar().getMaximum());
                    layout(frame.getContentPane());
                    capture(frame.getContentPane(), output.resolve("main-" + width + ".png"));
                }
                Constructor<ScoringDialog> ctor = ScoringDialog.class.getDeclaredConstructor(JFrame.class, BoardState.class,
                        EngineVerdict.class, FinalScore.class);
                ctor.setAccessible(true);
                ScoringDialog dialog = ctor.newInstance(frame, position, verdict, verdict.score());
                try {
                    dialog.addNotify(); dialog.validate(); layout(dialog.getContentPane());
                    capture(dialog.getContentPane(), output.resolve("score.png"));
                } finally { dialog.dispose(); }
                System.out.println("Rendered " + output);
            } catch (Exception e) { throw new RuntimeException(e); }
            finally { frame.dispose(); }
        });
    }

    private static Object narrative(int move, String kind, String meta, String text) throws Exception {
        Class<?> kindClass = Class.forName("com.yiyan.go.ui.MainFrame$MessageKind");
        Class<?> narrative = Class.forName("com.yiyan.go.ui.MainFrame$Narrative");
        @SuppressWarnings({"rawtypes", "unchecked"}) Object value = Enum.valueOf((Class) kindClass, kind);
        Constructor<?> ctor = narrative.getDeclaredConstructor(int.class, kindClass, String.class, String.class);
        ctor.setAccessible(true);
        return ctor.newInstance(move, value, meta, text);
    }
    private static Object field(Object target, String name) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); return field.get(target);
    }
    private static void set(Object target, String name, Object value) throws Exception {
        Field field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
    private static void layout(Container container) {
        container.doLayout();
        for (Component component : container.getComponents()) if (component instanceof Container child) layout(child);
    }
    private static void capture(Container component, Path path) throws Exception {
        BufferedImage image = new BufferedImage(component.getWidth(), component.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics(); component.printAll(graphics); graphics.dispose();
        ImageIO.write(image, "png", path.toFile());
    }
}
