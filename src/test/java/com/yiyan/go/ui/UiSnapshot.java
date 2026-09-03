package com.yiyan.go.ui;

import javax.imageio.ImageIO;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;

public final class UiSnapshot {
    private UiSnapshot() {
    }

    public static void main(String[] args) throws Exception {
        String output = args.length == 0 ? "out/ui-preview.png" : args[0];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Theme.installDefaults();
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                Theme.installDefaults();
                MainFrame frame = new MainFrame();
                frame.setSize(1420, 880);
                frame.addNotify();
                frame.validate();
                frame.getContentPane().doLayout();

                BufferedImage image = new BufferedImage(1420, 880, BufferedImage.TYPE_INT_ARGB);
                Graphics2D graphics = image.createGraphics();
                frame.getContentPane().printAll(graphics);
                graphics.dispose();

                File file = new File(output);
                File parent = file.getParentFile();
                if (parent != null) parent.mkdirs();
                ImageIO.write(image, "png", file);
                frame.dispose();
                System.out.println("UI 快照已生成: " + file.getAbsolutePath());
            } catch (Exception exception) {
                throw new RuntimeException(exception);
            }
        });
    }
}
