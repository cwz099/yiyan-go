package com.yiyan.go.ui;

import com.yiyan.go.game.GameSettings;
import com.yiyan.go.game.Stone;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JSpinner;
import javax.swing.KeyStroke;
import javax.swing.SpinnerNumberModel;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridLayout;
import java.text.ParseException;

public final class GameSettingsDialog {
    private GameSettingsDialog() {
    }

    /** Returns new-game preferences, or null when the user closes/cancels the dialog. */
    public static GameSettings showDialog(JFrame owner, GameSettings current) {
        JDialog dialog = new JDialog(owner, "对局设置 · 弈言", true);
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        GameSettings[] result = {null};
        JPanel content = new JPanel(new BorderLayout(0, 22));
        content.setBackground(Theme.CANVAS);
        content.setBorder(BorderFactory.createEmptyBorder(28, 28, 24, 28));
        dialog.setContentPane(content);

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("按你的节奏，开始一局");
        title.setFont(Theme.display(23));
        title.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(title);
        heading.add(Box.createVerticalStrut(10));
        JLabel hint = new JLabel("这些设置会用于新棋局，不会改变已经保存的对局。");
        hint.setFont(Theme.body(12));
        hint.setForeground(Theme.TEXT_SECONDARY);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);
        heading.add(hint);
        content.add(heading, BorderLayout.NORTH);

        RoundedPanel form = new RoundedPanel(Theme.SURFACE, Theme.BORDER_SOFT, 16);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));
        form.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));

        JComboBox<String> size = new JComboBox<>(new String[]{"9 路 · 轻松一局", "13 路 · 从容练习", "19 路 · 完整棋盘"});
        size.setSelectedIndex(current.boardSize() == 9 ? 0 : current.boardSize() == 13 ? 1 : 2);
        JComboBox<String> color = new JComboBox<>(new String[]{"执黑 · 你先行", "执白 · 对手先行"});
        color.setSelectedIndex(current.humanColor() == Stone.BLACK ? 0 : 1);
        JSpinner komi = new JSpinner(new SpinnerNumberModel(current.komi(), 0.0, 30.0, 0.5));
        komi.setEditor(new JSpinner.NumberEditor(komi, "0.#"));
        size.setFont(Theme.body(13));
        color.setFont(Theme.body(13));
        komi.setFont(Theme.body(13));
        size.setBackground(Theme.SURFACE);
        color.setBackground(Theme.SURFACE);
        form.add(row("棋盘大小", size));
        form.add(Box.createVerticalStrut(14));
        form.add(row("你的棋色", color));
        form.add(Box.createVerticalStrut(14));
        form.add(row("白棋贴目", komi));
        form.add(Box.createVerticalStrut(18));
        JCheckBox undo = checkBox("允许悔棋", current.allowUndo());
        JCheckBox fallback = checkBox("DeepSeek 请求失败时，由本地陪练接续", current.autoFallback());
        form.add(undo);
        form.add(Box.createVerticalStrut(8));
        form.add(fallback);
        form.add(Box.createVerticalStrut(16));
        JLabel rules = new JLabel("中国面积规则 · 禁止自杀 · 简单劫");
        rules.setFont(Theme.body(12));
        rules.setForeground(Theme.TEXT_MUTED);
        rules.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(rules);
        content.add(form, BorderLayout.CENTER);

        JPanel bottom = new JPanel(new BorderLayout(0, 12));
        bottom.setOpaque(false);
        JLabel warning = new JLabel("应用后将结束当前对局并新开一局。");
        warning.setFont(Theme.body(12));
        warning.setForeground(Theme.WARNING);
        bottom.add(warning, BorderLayout.NORTH);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        SoftButton cancel = new SoftButton("先不改", SoftButton.Style.SECONDARY);
        SoftButton apply = new SoftButton("应用并新开一局", SoftButton.Style.PRIMARY);
        cancel.addActionListener(event -> dialog.dispose());
        apply.addActionListener(event -> {
            try {
                komi.commitEdit();
                int selectedSize = new int[]{9, 13, 19}[size.getSelectedIndex()];
                result[0] = new GameSettings(selectedSize, ((Number) komi.getValue()).doubleValue(),
                        color.getSelectedIndex() == 0 ? Stone.BLACK : Stone.WHITE,
                        undo.isSelected(), fallback.isSelected());
                dialog.dispose();
            } catch (ParseException | IllegalArgumentException exception) {
                JOptionPane.showMessageDialog(dialog, "贴目请输入 0 到 30 之间的整数或半目。", "再确认一下贴目", JOptionPane.WARNING_MESSAGE);
            }
        });
        actions.add(cancel);
        actions.add(apply);
        bottom.add(actions, BorderLayout.CENTER);
        content.add(bottom, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(apply);
        dialog.getRootPane().registerKeyboardAction(event -> dialog.dispose(),
                KeyStroke.getKeyStroke("ESCAPE"), javax.swing.JComponent.WHEN_IN_FOCUSED_WINDOW);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(520, dialog.getHeight()));
        dialog.setSize(Math.max(520, dialog.getWidth()), dialog.getHeight());
        dialog.setResizable(false);
        dialog.setLocationRelativeTo(owner);
        dialog.setVisible(true);
        return result[0];
    }

    private static JPanel row(String text, javax.swing.JComponent input) {
        JPanel row = new JPanel(new GridLayout(1, 2, 18, 0));
        row.setOpaque(false);
        row.setAlignmentX(Component.LEFT_ALIGNMENT);
        row.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        row.setPreferredSize(new Dimension(400, 36));
        JLabel label = new JLabel(text);
        label.setFont(Theme.bodyMedium(13));
        label.setLabelFor(input);
        row.add(label);
        row.add(input);
        return row;
    }

    private static JCheckBox checkBox(String text, boolean selected) {
        JCheckBox box = new JCheckBox(text, selected);
        box.setOpaque(false);
        box.setFont(Theme.body(13));
        box.setForeground(Theme.TEXT);
        box.setAlignmentX(Component.LEFT_ALIGNMENT);
        return box;
    }
}
