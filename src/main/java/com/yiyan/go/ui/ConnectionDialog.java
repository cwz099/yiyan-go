package com.yiyan.go.ui;

import com.yiyan.go.ai.DeepSeekConfig;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPasswordField;
import javax.swing.JTextField;
import javax.swing.SwingConstants;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.Window;
import java.net.URI;

@SuppressWarnings("serial")
public final class ConnectionDialog extends JDialog {
    public record Result(boolean confirmed, DeepSeekConfig config) {
        public static Result cancelled() {
            return new Result(false, null);
        }

        public static Result local() {
            return new Result(true, null);
        }
    }

    private final JPasswordField keyField = new JPasswordField();
    private final JTextField endpointField = new JTextField(DeepSeekConfig.DEFAULT_ENDPOINT);
    private final JTextField modelField = new JTextField(DeepSeekConfig.DEFAULT_MODEL);
    private final JLabel errorLabel = new JLabel(" ");
    private Result result = Result.cancelled();

    private ConnectionDialog(Window owner) {
        super(owner, "连接 DeepSeek", ModalityType.APPLICATION_MODAL);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setResizable(false);
        setContentPane(createContent());
        getRootPane().setBorder(BorderFactory.createEmptyBorder());
        pack();
        setLocationRelativeTo(owner);
        String environmentKey = System.getenv("DEEPSEEK_API_KEY");
        if (environmentKey != null && !environmentKey.isBlank()) {
            keyField.setText(environmentKey);
        }
    }

    public static Result showDialog(JFrame owner) {
        ConnectionDialog dialog = new ConnectionDialog(owner);
        dialog.setVisible(true);
        return dialog.result;
    }

    private JPanel createContent() {
        RoundedPanel root = new RoundedPanel(Theme.SURFACE, Theme.BORDER, 20);
        root.setLayout(new BorderLayout());
        root.setBorder(BorderFactory.createEmptyBorder(28, 30, 24, 30));

        JPanel form = new JPanel();
        form.setOpaque(false);
        form.setLayout(new BoxLayout(form, BoxLayout.Y_AXIS));

        JLabel eyebrow = new JLabel("对手设置");
        eyebrow.setFont(Theme.bodyMedium(12));
        eyebrow.setForeground(Theme.ACCENT_DARK);
        eyebrow.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel title = new JLabel("连接 DeepSeek");
        title.setFont(Theme.display(26));
        title.setForeground(Theme.TEXT);
        title.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel intro = new JLabel("连接后即可对弈，并查看落子说明。");
        intro.setFont(Theme.body(13));
        intro.setForeground(Theme.TEXT_SECONDARY);
        intro.setAlignmentX(Component.LEFT_ALIGNMENT);

        form.add(eyebrow);
        form.add(Box.createVerticalStrut(7));
        form.add(title);
        form.add(Box.createVerticalStrut(11));
        form.add(intro);
        form.add(Box.createVerticalStrut(23));
        form.add(field("API 密钥", keyField, "仅本次运行有效"));
        form.add(Box.createVerticalStrut(15));
        form.add(field("API 地址", endpointField, "支持兼容接口"));
        form.add(Box.createVerticalStrut(15));
        form.add(field("模型", modelField, "可选 deepseek-v4-flash / deepseek-v4-pro"));
        form.add(Box.createVerticalStrut(10));

        errorLabel.setFont(Theme.body(12));
        errorLabel.setForeground(Theme.DANGER);
        errorLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        form.add(errorLabel);

        JPanel actions = new JPanel(new BorderLayout(12, 0));
        actions.setOpaque(false);
        actions.setBorder(BorderFactory.createEmptyBorder(19, 0, 0, 0));
        SoftButton local = new SoftButton("使用本地陪练", SoftButton.Style.GHOST);
        SoftButton connect = new SoftButton("连接 DeepSeek", SoftButton.Style.PRIMARY);
        local.addActionListener(event -> {
            result = Result.local();
            dispose();
        });
        connect.addActionListener(event -> connect());
        actions.add(local, BorderLayout.WEST);
        actions.add(connect, BorderLayout.EAST);

        root.add(form, BorderLayout.CENTER);
        root.add(actions, BorderLayout.SOUTH);
        getRootPane().setDefaultButton(connect);
        return root;
    }

    private JPanel field(String labelText, JTextField field, String hintText) {
        JPanel group = new JPanel();
        group.setOpaque(false);
        group.setLayout(new BoxLayout(group, BoxLayout.Y_AXIS));
        group.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel label = new JLabel(labelText);
        label.setFont(Theme.bodyMedium(12));
        label.setForeground(Theme.TEXT);
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        field.setFont(Theme.body(13));
        field.setForeground(Theme.TEXT);
        field.setCaretColor(Theme.ACCENT_DARK);
        field.setBackground(Theme.SURFACE);
        field.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(Theme.BORDER, 1, true),
                BorderFactory.createEmptyBorder(8, 10, 8, 10)));
        field.setMaximumSize(new Dimension(Integer.MAX_VALUE, 36));
        field.setPreferredSize(new Dimension(440, 36));
        field.setAlignmentX(Component.LEFT_ALIGNMENT);

        JLabel hint = new JLabel(hintText, SwingConstants.LEFT);
        hint.setFont(Theme.body(11));
        hint.setForeground(Theme.TEXT_MUTED);
        hint.setAlignmentX(Component.LEFT_ALIGNMENT);

        group.add(label);
        group.add(Box.createVerticalStrut(5));
        group.add(field);
        group.add(Box.createVerticalStrut(3));
        group.add(hint);
        Dimension size = group.getPreferredSize();
        group.setMinimumSize(new Dimension(0, size.height));
        group.setMaximumSize(new Dimension(Integer.MAX_VALUE, size.height));
        return group;
    }

    private void connect() {
        try {
            String key = new String(keyField.getPassword());
            URI endpoint = URI.create(endpointField.getText().trim());
            DeepSeekConfig config = new DeepSeekConfig(key.trim(), endpoint,
                    modelField.getText().trim(), java.time.Duration.ofSeconds(45));
            result = new Result(true, config);
            dispose();
        } catch (RuntimeException exception) {
            errorLabel.setText(exception.getMessage() == null ? "请检查这些设置" : exception.getMessage());
        }
    }
}
