package com.yiyan.go.ui;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.Move;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;

import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.KeyStroke;
import javax.accessibility.AccessibleContext;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.LinearGradientPaint;
import java.awt.RadialGradientPaint;
import java.awt.RenderingHints;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.RoundRectangle2D;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import java.util.List;
import java.util.Set;

@SuppressWarnings("serial")
public final class BoardPanel extends JPanel {
    private final Supplier<BoardState> stateSupplier;
    private final BooleanSupplier interactiveSupplier;
    private final BiConsumer<Integer, Integer> moveHandler;
    private Point hovered;
    private Point keyboardPoint = new Point(0, 0);
    private int displayedSize = -1;
    private Set<Point> deadMarks = Set.of();
    private Set<Point> neutralMarks = Set.of();

    public void setScoreMarks(Set<Point> dead, Set<Point> neutral) {
        deadMarks = Set.copyOf(dead);
        neutralMarks = Set.copyOf(neutral);
        repaint();
    }

    public BoardPanel(Supplier<BoardState> stateSupplier,
                      BooleanSupplier interactiveSupplier,
                      BiConsumer<Integer, Integer> moveHandler) {
        this.stateSupplier = stateSupplier;
        this.interactiveSupplier = interactiveSupplier;
        this.moveHandler = moveHandler;
        setOpaque(false);
        setFocusable(true);
        setCursor(Cursor.getPredefinedCursor(Cursor.CROSSHAIR_CURSOR));
        setPreferredSize(new Dimension(680, 680));
        setMinimumSize(new Dimension(390, 390));
        syncBoardSize();
        getAccessibleContext().setAccessibleDescription("使用方向键移动焦点，按 Enter 落子");

        MouseAdapter mouse = new MouseAdapter() {
            @Override
            public void mouseMoved(MouseEvent event) {
                Point next = pointAt(event.getX(), event.getY());
                if (next != null && stateSupplier.get().stoneAt(next.x(), next.y()) != Stone.EMPTY) {
                    next = null;
                }
                hovered = next;
                repaint();
            }

            @Override
            public void mouseExited(MouseEvent event) {
                hovered = null;
                repaint();
            }

            @Override
            public void mousePressed(MouseEvent event) {
                requestFocusInWindow();
                if (!interactiveSupplier.getAsBoolean()) return;
                Point point = pointAt(event.getX(), event.getY());
                if (point != null) {
                    keyboardPoint = point;
                    moveHandler.accept(point.x(), point.y());
                }
            }
        };
        addMouseMotionListener(mouse);
        addMouseListener(mouse);
        installKeyboardActions();
    }

    private void installKeyboardActions() {
        bind("LEFT", -1, 0);
        bind("RIGHT", 1, 0);
        bind("UP", 0, -1);
        bind("DOWN", 0, 1);
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ENTER"), "play");
        getActionMap().put("play", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                syncBoardSize();
                if (!interactiveSupplier.getAsBoolean()) return;
                moveHandler.accept(keyboardPoint.x(), keyboardPoint.y());
            }
        });
    }

    private void bind(String key, int dx, int dy) {
        String actionName = "move-" + key.toLowerCase();
        getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(key), actionName);
        getActionMap().put(actionName, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent event) {
                syncBoardSize();
                int size = stateSupplier.get().size();
                keyboardPoint = new Point(
                        Math.max(0, Math.min(size - 1, keyboardPoint.x() + dx)),
                        Math.max(0, Math.min(size - 1, keyboardPoint.y() + dy)));
                repaint();
            }
        });
    }

    @Override
    protected void paintComponent(Graphics graphics) {
        super.paintComponent(graphics);
        syncBoardSize();
        Graphics2D g = (Graphics2D) graphics.create();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        BoardState state = stateSupplier.get();
        Geometry geometry = geometry(state.size());

        paintBoardSurface(g, geometry);
        paintGrid(g, geometry, state.size());
        paintCoordinates(g, geometry, state.size());
        paintStones(g, geometry, state);
        paintScoreMarks(g, geometry, state.size());
        paintHover(g, geometry, state);
        paintKeyboardFocus(g, geometry);
        g.dispose();
    }

    private void paintScoreMarks(Graphics2D g, Geometry geometry, int size) {
        float radius = geometry.cell() * 0.20f;
        g.setStroke(new BasicStroke(Math.max(2f, geometry.cell() * 0.06f)));
        g.setColor(Theme.DANGER);
        for (Point point : deadMarks) {
            if (point.x() < 0 || point.y() < 0 || point.x() >= size || point.y() >= size) continue;
            float x = geometry.originX() + geometry.cell() * point.x();
            float y = geometry.originY() + geometry.cell() * point.y();
            g.draw(new java.awt.geom.Line2D.Float(x - radius, y - radius, x + radius, y + radius));
            g.draw(new java.awt.geom.Line2D.Float(x - radius, y + radius, x + radius, y - radius));
        }
        g.setColor(Theme.TEXT_MUTED);
        for (Point point : neutralMarks) {
            if (point.x() < 0 || point.y() < 0 || point.x() >= size || point.y() >= size) continue;
            float x = geometry.originX() + geometry.cell() * point.x();
            float y = geometry.originY() + geometry.cell() * point.y();
            g.draw(new java.awt.geom.Rectangle2D.Float(x - radius / 2, y - radius / 2, radius, radius));
        }
    }

    private void paintBoardSurface(Graphics2D g, Geometry geometry) {
        float x = geometry.surfaceX();
        float y = geometry.surfaceY();
        float size = geometry.surfaceSize();
        g.setColor(new Color(40, 30, 20, 18));
        g.fill(new RoundRectangle2D.Float(x + 2, y + 5, size, size, 18, 18));
        LinearGradientPaint wood = new LinearGradientPaint(x, y, x + size, y + size,
                new float[]{0f, 0.52f, 1f},
                new Color[]{new Color(0xE6C994), Theme.BOARD, new Color(0xCFA66B)});
        g.setPaint(wood);
        g.fill(new RoundRectangle2D.Float(x, y, size, size, 18, 18));
        g.setColor(new Color(255, 255, 255, 38));
        g.draw(new RoundRectangle2D.Float(x + 0.5f, y + 0.5f, size - 1, size - 1, 18, 18));
    }

    private void paintGrid(Graphics2D g, Geometry geometry, int boardSize) {
        g.setColor(new Color(Theme.GRID.getRed(), Theme.GRID.getGreen(), Theme.GRID.getBlue(), 185));
        g.setStroke(new BasicStroke(Math.max(0.8f, geometry.cell() * 0.035f)));
        float endX = geometry.originX() + geometry.cell() * (boardSize - 1);
        float endY = geometry.originY() + geometry.cell() * (boardSize - 1);
        for (int i = 0; i < boardSize; i++) {
            float positionX = geometry.originX() + i * geometry.cell();
            float positionY = geometry.originY() + i * geometry.cell();
            g.drawLine(Math.round(geometry.originX()), Math.round(positionY), Math.round(endX), Math.round(positionY));
            g.drawLine(Math.round(positionX), Math.round(geometry.originY()), Math.round(positionX), Math.round(endY));
        }

        float radius = Math.max(2.3f, geometry.cell() * 0.11f);
        for (Point star : starPoints(boardSize)) {
            float cx = geometry.originX() + star.x() * geometry.cell();
            float cy = geometry.originY() + star.y() * geometry.cell();
            g.fill(new Ellipse2D.Float(cx - radius, cy - radius, radius * 2, radius * 2));
        }
    }

    static List<Point> starPoints(int boardSize) {
        if (boardSize == 19) {
            return List.of(new Point(3, 3), new Point(3, 9), new Point(3, 15),
                    new Point(9, 3), new Point(9, 9), new Point(9, 15),
                    new Point(15, 3), new Point(15, 9), new Point(15, 15));
        }
        if (boardSize == 9 || boardSize == 13) {
            int low = boardSize == 9 ? 2 : 3;
            int high = boardSize - 1 - low;
            return List.of(new Point(low, low), new Point(low, high),
                    new Point(high, low), new Point(high, high),
                    new Point(boardSize / 2, boardSize / 2));
        }
        return List.of(new Point(boardSize / 2, boardSize / 2));
    }

    @Override
    public AccessibleContext getAccessibleContext() {
        AccessibleContext context = super.getAccessibleContext();
        if (stateSupplier != null) {
            context.setAccessibleName(stateSupplier.get().size() + " 路围棋棋盘");
        }
        return context;
    }

    private void syncBoardSize() {
        int size = stateSupplier.get().size();
        if (displayedSize != size) {
            displayedSize = size;
            keyboardPoint = new Point(size / 2, size / 2);
            hovered = null;
            getAccessibleContext().setAccessibleName(size + " 路围棋棋盘");
        }
    }

    private void paintCoordinates(Graphics2D g, Geometry geometry, int size) {
        g.setFont(Theme.mono(Math.min(14, Math.max(9, Math.round(geometry.cell() * 0.34f)))));
        g.setColor(new Color(Theme.GRID.getRed(), Theme.GRID.getGreen(), Theme.GRID.getBlue(), 175));
        FontMetrics metrics = g.getFontMetrics();
        float margin = geometry.originX() - geometry.surfaceX();
        String columns = "ABCDEFGHJKLMNOPQRST";
        for (int i = 0; i < size; i++) {
            float coordinateX = geometry.originX() + i * geometry.cell();
            float coordinateY = geometry.originY() + i * geometry.cell();
            String column = Character.toString(columns.charAt(i));
            g.drawString(column, coordinateX - metrics.stringWidth(column) / 2f,
                    geometry.originY() - geometry.cell() * 0.45f - 6f);
            String row = Integer.toString(size - i);
            g.drawString(row, geometry.originX() - geometry.cell() * 0.45f - 11f - metrics.stringWidth(row) / 2f,
                    coordinateY + metrics.getAscent() * 0.38f);
        }
    }

    private void paintStones(Graphics2D g, Geometry geometry, BoardState state) {
        float diameter = geometry.cell() * 0.90f;
        float radius = diameter / 2f;
        Move last = state.lastMove();
        for (int x = 0; x < state.size(); x++) {
            for (int y = 0; y < state.size(); y++) {
                Stone stone = state.stoneAt(x, y);
                if (stone == Stone.EMPTY) continue;
                float cx = geometry.originX() + x * geometry.cell();
                float cy = geometry.originY() + y * geometry.cell();

                g.setColor(new Color(30, 22, 15, 48));
                g.fill(new Ellipse2D.Float(cx - radius + 1.5f, cy - radius + 3f, diameter, diameter));

                Point2D center = new Point2D.Float(cx - radius * 0.28f, cy - radius * 0.32f);
                if (stone == Stone.BLACK) {
                    g.setPaint(new RadialGradientPaint(center, diameter * 0.74f,
                            new float[]{0f, 0.55f, 1f},
                            new Color[]{new Color(0x5C5954), new Color(0x292826), new Color(0x11110F)}));
                } else {
                    g.setPaint(new RadialGradientPaint(center, diameter * 0.78f,
                            new float[]{0f, 0.62f, 1f},
                            new Color[]{Color.WHITE, new Color(0xF5F1E8), new Color(0xD7D0C4)}));
                }
                g.fill(new Ellipse2D.Float(cx - radius, cy - radius, diameter, diameter));
                g.setColor(stone == Stone.BLACK ? new Color(255, 255, 255, 26) : new Color(70, 58, 46, 65));
                g.setStroke(new BasicStroke(0.8f));
                g.draw(new Ellipse2D.Float(cx - radius + 0.5f, cy - radius + 0.5f, diameter - 1, diameter - 1));

                if (last != null && !last.pass() && last.point().x() == x && last.point().y() == y) {
                    float mark = Math.max(4f, geometry.cell() * 0.17f);
                    g.setColor(stone == Stone.BLACK ? new Color(0xE7BBA8) : Theme.ACCENT_DARK);
                    g.setStroke(new BasicStroke(Math.max(1.5f, geometry.cell() * 0.055f)));
                    g.draw(new Ellipse2D.Float(cx - mark, cy - mark, mark * 2, mark * 2));
                }
            }
        }
    }

    private void paintHover(Graphics2D g, Geometry geometry, BoardState state) {
        if (hovered == null || !interactiveSupplier.getAsBoolean()
                || state.stoneAt(hovered.x(), hovered.y()) != Stone.EMPTY) return;
        float cx = geometry.originX() + hovered.x() * geometry.cell();
        float cy = geometry.originY() + hovered.y() * geometry.cell();
        float diameter = geometry.cell() * 0.82f;
        g.setColor(state.turn() == Stone.BLACK ? new Color(32, 30, 27, 95) : new Color(255, 252, 245, 170));
        g.fill(new Ellipse2D.Float(cx - diameter / 2, cy - diameter / 2, diameter, diameter));
        g.setColor(new Color(Theme.ACCENT.getRed(), Theme.ACCENT.getGreen(), Theme.ACCENT.getBlue(), 180));
        g.setStroke(new BasicStroke(1.4f));
        g.draw(new Ellipse2D.Float(cx - diameter / 2, cy - diameter / 2, diameter, diameter));
    }

    private void paintKeyboardFocus(Graphics2D g, Geometry geometry) {
        if (!isFocusOwner()) return;
        float cx = geometry.originX() + keyboardPoint.x() * geometry.cell();
        float cy = geometry.originY() + keyboardPoint.y() * geometry.cell();
        float diameter = geometry.cell() * 1.03f;
        g.setColor(new Color(255, 253, 247, 225));
        g.setStroke(new BasicStroke(4f));
        g.draw(new Ellipse2D.Float(cx - diameter / 2, cy - diameter / 2, diameter, diameter));
        g.setColor(Theme.INK);
        g.setStroke(new BasicStroke(1.8f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, new float[]{3.5f, 3.5f}, 0));
        g.draw(new Ellipse2D.Float(cx - diameter / 2, cy - diameter / 2, diameter, diameter));
    }

    private Point pointAt(int mouseX, int mouseY) {
        syncBoardSize();
        BoardState state = stateSupplier.get();
        Geometry geometry = geometry(state.size());
        int x = Math.round((mouseX - geometry.originX()) / geometry.cell());
        int y = Math.round((mouseY - geometry.originY()) / geometry.cell());
        if (x < 0 || x >= state.size() || y < 0 || y >= state.size()) return null;
        float px = geometry.originX() + x * geometry.cell();
        float py = geometry.originY() + y * geometry.cell();
        if (Math.hypot(mouseX - px, mouseY - py) > geometry.cell() * 0.48) return null;
        return new Point(x, y);
    }

    private Geometry geometry(int boardSize) {
        float available = Math.min(getWidth(), getHeight()) - 24f;
        float surface = Math.max(260f, available);
        float surfaceX = (getWidth() - surface) / 2f;
        float surfaceY = (getHeight() - surface) / 2f;
        // Reserve space for half a stone plus coordinate labels, including enlarged 9x9 stones.
        float margin = Math.max(30f, Math.max(surface * 0.073f, (0.55f * surface + 18f * (boardSize - 1)) / (boardSize + 0.1f)));
        float grid = surface - margin * 2;
        float cell = grid / (boardSize - 1);
        return new Geometry(surfaceX, surfaceY, surface, surfaceX + margin, surfaceY + margin, cell);
    }

    private record Geometry(float surfaceX, float surfaceY, float surfaceSize,
                            float originX, float originY, float cell) {
    }
}
