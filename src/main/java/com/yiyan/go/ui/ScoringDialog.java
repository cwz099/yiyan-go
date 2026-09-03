package com.yiyan.go.ui;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.FinalScore;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.ScoreAdjudication;
import com.yiyan.go.engine.EngineVerdict;
import javax.swing.*;
import java.awt.*;
import java.util.HashSet;
import java.util.Set;

/** Explicit human adjudication; only the returned CONFIRM result authorizes saving a score. */
@SuppressWarnings("serial")
public final class ScoringDialog extends JDialog {
    public enum Action { PENDING, CONTINUE, CONFIRM }
    public record Result(Action action, FinalScore score) { }

    private final BoardState position;
    private final Set<Point> dead = new HashSet<>();
    private final Set<Point> neutral = new HashSet<>();
    private final BoardPanel board;
    private final JComboBox<String> mode = new JComboBox<>(new String[]{"标记死子（整块）", "标记中立区域"});
    private final JCheckBox acknowledged = new JCheckBox("按当前标记确认结果");
    private final SoftButton confirm = new SoftButton("确认结算", SoftButton.Style.PRIMARY);
    private final JTextArea counts = new JTextArea();
    private final JLabel totals = new JLabel();
    private final JLabel hint = new JLabel("红叉：死子 · 灰框：中立区域");
    private FinalScore preview;
    private Result result;

    private ScoringDialog(JFrame owner, BoardState position) {
        this(owner, position, null, null);
    }

    private ScoringDialog(JFrame owner, BoardState position, EngineVerdict engine, FinalScore confirmed) {
        super(owner, "把这盘棋数清楚 · 弈言", true);
        this.position = position.copy();
        FinalScore initial = confirmed != null ? confirmed : engine == null ? null : engine.score();
        if (initial != null) {
            dead.addAll(initial.deadStones());
            neutral.addAll(initial.neutralPoints());
        }
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1080, 780);
        setMinimumSize(new Dimension(940, 690));
        setLocationRelativeTo(owner);
        JPanel root = new JPanel(new BorderLayout(20, 18));
        root.setBackground(Theme.CANVAS);
        root.setBorder(BorderFactory.createEmptyBorder(26, 28, 22, 28));
        JPanel heading = new JPanel(new BorderLayout(0, 9));
        heading.setOpaque(false);
        JLabel title = new JLabel("把这盘棋，数清楚");
        title.setFont(Theme.display(25));
        title.setForeground(Theme.TEXT);
        JLabel note = new JLabel(engine == null ? "点击棋块调整死子，或继续对弈。"
                : engine.engine() + " · " + (engine.consistent() ? "死活与面积已核对" : "估分 " + engine.rawScore() + "，建议继续收官"));
        note.setFont(Theme.body(12));
        note.setForeground(Theme.TEXT_SECONDARY);
        heading.add(title, BorderLayout.NORTH);
        heading.add(note, BorderLayout.SOUTH);
        root.add(heading, BorderLayout.NORTH);

        JPanel workspace = new JPanel(new BorderLayout(18, 0));
        workspace.setOpaque(false);
        board = new BoardPanel(() -> this.position, () -> true, this::togglePoint);
        board.getAccessibleContext().setAccessibleDescription("结算棋盘：点击整块棋切换死子；可选中立模式后点击空区。方向键和 Enter 也可操作。");
        workspace.add(board, BorderLayout.CENTER);
        RoundedPanel details = new RoundedPanel(Theme.SURFACE, Theme.BORDER, 16);
        details.setLayout(new BorderLayout(0, 18));
        details.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
        details.setPreferredSize(new Dimension(310, 530));
        JPanel controls = new JPanel();
        controls.setOpaque(false);
        controls.setLayout(new BoxLayout(controls, BoxLayout.Y_AXIS));
        JLabel label = new JLabel("盘面标记");
        label.setFont(Theme.bodyMedium(13));
        label.setAlignmentX(LEFT_ALIGNMENT);
        mode.setFont(Theme.body(12));
        mode.setMaximumSize(new Dimension(280, 36));
        mode.setAlignmentX(LEFT_ALIGNMENT);
        mode.getAccessibleContext().setAccessibleName("结算标记模式");
        controls.add(label);
        controls.add(Box.createVerticalStrut(10));
        controls.add(mode);
        controls.add(Box.createVerticalStrut(18));
        totals.setFont(Theme.display(20));
        totals.setForeground(Theme.ACCENT_DARK);
        totals.setAlignmentX(LEFT_ALIGNMENT);
        controls.add(totals);
        controls.add(Box.createVerticalStrut(14));
        counts.setEditable(false);
        counts.setOpaque(false);
        counts.setFont(Theme.body(13));
        counts.setForeground(Theme.TEXT);
        counts.setLineWrap(true);
        counts.setWrapStyleWord(true);
        counts.setAlignmentX(LEFT_ALIGNMENT);
        counts.setMaximumSize(new Dimension(280, 280));
        counts.getAccessibleContext().setAccessibleName("结算明细预览");
        controls.add(counts);
        controls.add(Box.createVerticalStrut(15));
        SoftButton reset = new SoftButton("清除全部标记", SoftButton.Style.GHOST);
        reset.setAlignmentX(LEFT_ALIGNMENT);
        reset.addActionListener(event -> { dead.clear(); neutral.clear(); marksChanged("已清除标记，请重新核对。"); });
        controls.add(reset);
        JScrollPane detailScroll = new JScrollPane(controls, ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        detailScroll.setBorder(BorderFactory.createEmptyBorder());
        detailScroll.setOpaque(false);
        detailScroll.getViewport().setOpaque(false);
        details.add(detailScroll, BorderLayout.CENTER);
        JPanel consent = new JPanel(new BorderLayout(0, 12));
        consent.setOpaque(false);
        acknowledged.setFont(Theme.body(12));
        acknowledged.setForeground(Theme.TEXT);
        acknowledged.setOpaque(false);
        acknowledged.addActionListener(event -> confirm.setEnabled(acknowledged.isSelected()));
        consent.add(acknowledged, BorderLayout.NORTH);
        details.add(consent, BorderLayout.SOUTH);
        workspace.add(details, BorderLayout.EAST);
        root.add(workspace, BorderLayout.CENTER);

        JPanel footer = new JPanel(new BorderLayout(0, 14));
        footer.setOpaque(false);
        hint.setFont(Theme.body(11));
        hint.setForeground(Theme.TEXT_SECONDARY);
        footer.add(hint, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 10, 0));
        actions.setOpaque(false);
        SoftButton pending = new SoftButton("返回待确认", SoftButton.Style.GHOST);
        pending.addActionListener(event -> { result = new Result(Action.PENDING, preview); dispose(); });
        SoftButton resume = new SoftButton("有争议，继续对弈", SoftButton.Style.SECONDARY);
        resume.addActionListener(event -> { result = new Result(Action.CONTINUE, preview); dispose(); });
        confirm.addActionListener(event -> {
            if (!acknowledged.isSelected()) return;
            result = new Result(Action.CONFIRM, preview);
            dispose();
        });
        actions.add(pending);
        actions.add(resume);
        actions.add(confirm);
        footer.add(actions, BorderLayout.SOUTH);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        refreshPreview();
    }

    public static Result showDialog(JFrame owner, BoardState board) {
        ScoringDialog dialog = new ScoringDialog(owner, board);
        dialog.setVisible(true);
        return dialog.result;
    }

    public static Result showDialog(JFrame owner, BoardState board, EngineVerdict engine, FinalScore confirmed) {
        ScoringDialog dialog = new ScoringDialog(owner, board, engine, confirmed);
        dialog.setVisible(true);
        return dialog.result;
    }

    private void togglePoint(int x, int y) {
        Point point = new Point(x, y);
        if (mode.getSelectedIndex() == 0) {
            Set<Point> group = ScoreAdjudication.groupAt(position, point);
            if (group.isEmpty()) { hint.setText("请点击一块棋；空点不能标成死子。"); return; }
            if (dead.containsAll(group)) dead.removeAll(group); else dead.addAll(group);
            // Restoring a stone can split a marked empty region; require a fresh neutral-region review.
            neutral.clear();
            marksChanged("死子已调整，中立标记已重置。");
        } else {
            Set<Point> region = ScoreAdjudication.emptyRegionAt(position, dead, point);
            if (region.isEmpty()) { hint.setText("请点击空区；保留的活子不能标成中立。"); return; }
            if (neutral.containsAll(region)) neutral.removeAll(region); else neutral.addAll(region);
            marksChanged("已切换整个空区的中立标记，请核对明细。");
        }
    }

    private void marksChanged(String text) {
        acknowledged.setSelected(false);
        hint.setText(text);
        refreshPreview();
    }

    private void refreshPreview() {
        preview = ScoreAdjudication.calculate(position, dead, neutral);
        result = new Result(Action.PENDING, preview);
        totals.setText(preview.winner() == com.yiyan.go.game.Stone.EMPTY ? "双方同分"
                : preview.winner().chineseName() + "胜 " + FinalScore.format(preview.margin()) + " 点");
        counts.setText("黑棋盘上子：" + preview.blackStones() + "\n黑棋围空：" + preview.blackTerritory()
                + "\n\n白棋盘上子：" + preview.whiteStones() + "\n白棋围空：" + preview.whiteTerritory()
                + "\n白棋贴目：" + FinalScore.format(preview.komi())
                + "\n\n不计归属的空点：" + preview.neutralCount() + "\n已标记死子：" + preview.deadStones().size()
                + "\n\n黑方总分：" + FinalScore.format(preview.blackTotal())
                + "\n白方总分：" + FinalScore.format(preview.whiteTotal()));
        counts.setCaretPosition(0);
        board.setScoreMarks(preview.deadStones(), preview.neutralPoints());
        confirm.setEnabled(acknowledged.isSelected());
    }
}
