package com.yiyan.go.ui;

import javax.swing.*;
import java.awt.*;

/** Transcript height is measured at the viewport's actual width, including wrapped metadata. */
@SuppressWarnings("serial")
final class TranscriptPanel extends JPanel implements Scrollable {
    TranscriptPanel() {
        setOpaque(false);
        setLayout(new LayoutManager() {
            public void addLayoutComponent(String name, Component component) { }
            public void removeLayoutComponent(Component component) { }
            public Dimension minimumLayoutSize(Container parent) { return new Dimension(0, 0); }
            public Dimension preferredLayoutSize(Container parent) {
                int width = contentWidth(), height = 0;
                for (Component child : getComponents()) height += measure(child, width);
                return new Dimension(width, height);
            }
            public void layoutContainer(Container parent) {
                int width = contentWidth(), y = 0;
                for (Component child : getComponents()) {
                    int height = measure(child, width);
                    child.setBounds(0, y, width, height);
                    y += height;
                }
            }
        });
    }

    private int contentWidth() {
        return Math.max(120, (getParent() instanceof JViewport viewport
                ? viewport.getExtentSize().width : getWidth() > 0 ? getWidth() : 320) - 6);
    }

    private int measure(Component child, int width) {
        if (child instanceof RoundedPanel panel) {
            Insets insets = panel.getInsets();
            int textWidth = Math.max(50, width - insets.left - insets.right);
            for (Component component : panel.getComponents()) {
                if (component instanceof JTextArea area) {
                    area.setSize(textWidth, Short.MAX_VALUE);
                    Dimension size = area.getUI().getPreferredSize(area);
                    size = new Dimension(textWidth, size.height);
                    area.setPreferredSize(size);
                    area.setMinimumSize(size);
                    area.setMaximumSize(size);
                }
            }
            panel.invalidate();
        }
        return child.getPreferredSize().height;
    }

    @Override public Dimension getPreferredScrollableViewportSize() { return new Dimension(320, 500); }
    @Override public int getScrollableUnitIncrement(Rectangle visible, int orientation, int direction) { return 20; }
    @Override public int getScrollableBlockIncrement(Rectangle visible, int orientation, int direction) { return Math.max(20, visible.height - 40); }
    @Override public boolean getScrollableTracksViewportWidth() { return true; }
    @Override public boolean getScrollableTracksViewportHeight() { return false; }
}
