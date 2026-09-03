package com.yiyan.go.ui;

import javax.swing.JButton;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

@SuppressWarnings("serial")
public final class SoftButton extends JButton {
    public enum Style {PRIMARY, SECONDARY, GHOST, ACCENT}

    private final Style style;
    private boolean hovered;

    public SoftButton(String text, Style style) {
        super(text);
        this.style = style;
        setFont(Theme.bodyMedium(13));
        setBorderPainted(false);
        setContentAreaFilled(false);
        setFocusPainted(false);
        setOpaque(false);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setPreferredSize(new Dimension(Math.max(92, getPreferredSize().width + 24), 38));
        setMinimumSize(new Dimension(76, 36));
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent event) {
                hovered = true;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hovered = false;
                repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        Color fill;
        Color border = null;
        Color foreground;
        switch (style) {
            case PRIMARY -> {
                fill = hovered ? Theme.blend(Theme.INK, Color.WHITE, 0.08f) : Theme.INK;
                foreground = Color.WHITE;
            }
            case ACCENT -> {
                fill = hovered ? Theme.ACCENT_DARK : Theme.ACCENT;
                foreground = Color.WHITE;
            }
            case SECONDARY -> {
                fill = hovered ? Theme.SURFACE_ALT : Theme.SURFACE;
                border = Theme.BORDER;
                foreground = Theme.TEXT;
            }
            default -> {
                fill = hovered ? Theme.SURFACE_ALT : new Color(0, 0, 0, 0);
                foreground = Theme.TEXT_SECONDARY;
            }
        }
        if (!isEnabled()) {
            fill = Theme.blend(fill, Theme.CANVAS, 0.55f);
            foreground = Theme.TEXT_MUTED;
        }
        RoundRectangle2D shape = new RoundRectangle2D.Float(0.5f, 0.5f,
                getWidth() - 1.5f, getHeight() - 1.5f, 11, 11);
        g.setColor(fill);
        g.fill(shape);
        if (border != null) {
            g.setColor(border);
            g.setStroke(new BasicStroke(1f));
            g.draw(shape);
        }
        if (isFocusOwner()) {
            g.setColor(style == Style.PRIMARY || style == Style.ACCENT ? Color.WHITE : Theme.ACCENT_DARK);
            g.setStroke(new BasicStroke(2f));
            g.draw(new RoundRectangle2D.Float(2.5f, 2.5f,
                    getWidth() - 5.5f, getHeight() - 5.5f, 8, 8));
        }
        setForeground(foreground);
        g.dispose();
        super.paintComponent(graphics);
    }
}
