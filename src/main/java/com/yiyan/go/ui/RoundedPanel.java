package com.yiyan.go.ui;

import javax.swing.JPanel;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.RoundRectangle2D;

@SuppressWarnings("serial")
public final class RoundedPanel extends JPanel {
    private Color fill;
    private Color stroke;
    private int radius;

    public RoundedPanel(Color fill, Color stroke, int radius) {
        this.fill = fill;
        this.stroke = stroke;
        this.radius = radius;
        setOpaque(false);
    }

    public void setFill(Color fill) {
        this.fill = fill;
        repaint();
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        float inset = stroke == null ? 0 : 0.5f;
        RoundRectangle2D shape = new RoundRectangle2D.Float(inset, inset,
                getWidth() - inset * 2 - 1, getHeight() - inset * 2 - 1, radius, radius);
        if (fill != null) {
            g.setColor(fill);
            g.fill(shape);
        }
        if (stroke != null) {
            g.setColor(stroke);
            g.setStroke(new BasicStroke(1f));
            g.draw(shape);
        }
        g.dispose();
        super.paintComponent(graphics);
    }
}
