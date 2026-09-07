package com.yiyan.go.ui;

import com.yiyan.go.diagnostics.AppLogs;
import com.yiyan.go.diagnostics.AppPaths;

import javax.swing.*;
import java.awt.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Read-only, bounded view of the local diagnostic log; never issues an API request. */
@SuppressWarnings("serial")
public final class LogsDialog extends JDialog {
    private final JTextArea text = new JTextArea();
    private final JLabel status = new JLabel(" ");
    private final SoftButton refresh = new SoftButton("刷新日志", SoftButton.Style.SECONDARY);
    private SwingWorker<String, Void> loader;

    private LogsDialog(JFrame owner) {
        super(owner, "运行与网络日志", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1040, 680);
        setMinimumSize(new Dimension(760, 480));
        setLocationRelativeTo(owner);
        JPanel root = new JPanel(new BorderLayout(0, 18));
        root.setBackground(Theme.CANVAS);
        root.setBorder(BorderFactory.createEmptyBorder(26, 28, 24, 28));
        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("让每一次对局与请求都有迹可循");
        title.setFont(Theme.display(23));
        JLabel note = new JLabel("仅显示最近 200 KB；包含对局、KataGo 引擎和网络请求状态，不保存密钥或原始响应正文。");
        note.setFont(Theme.body(12));
        note.setForeground(Theme.TEXT_SECONDARY);
        heading.add(title);
        heading.add(Box.createVerticalStrut(9));
        heading.add(note);
        JLabel directory = new JLabel(AppPaths.dataDirectory().toString());
        directory.setFont(Theme.body(11));
        directory.setForeground(Theme.TEXT_MUTED);
        heading.add(Box.createVerticalStrut(8));
        heading.add(directory);
        root.add(heading, BorderLayout.NORTH);
        text.setEditable(false);
        text.setLineWrap(true);
        text.setWrapStyleWord(false);
        text.setFont(Theme.mono(12));
        text.setBackground(Theme.SURFACE);
        text.setForeground(Theme.TEXT);
        text.setMargin(new Insets(14, 14, 14, 14));
        text.getAccessibleContext().setAccessibleName("脱敏运行日志");
        root.add(new JScrollPane(text), BorderLayout.CENTER);
        JPanel footer = new JPanel(new BorderLayout(10, 0));
        footer.setOpaque(false);
        status.setFont(Theme.body(11));
        status.setForeground(Theme.TEXT_MUTED);
        footer.add(status, BorderLayout.CENTER);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        SoftButton folder = new SoftButton("打开数据目录", SoftButton.Style.GHOST);
        folder.addActionListener(event -> openDirectory());
        refresh.addActionListener(event -> reload());
        SoftButton done = new SoftButton("完成", SoftButton.Style.PRIMARY);
        done.addActionListener(event -> dispose());
        actions.add(folder);
        actions.add(refresh);
        actions.add(done);
        footer.add(actions, BorderLayout.EAST);
        root.add(footer, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"),
                JComponent.WHEN_IN_FOCUSED_WINDOW);
        reload();
    }

    public static void showDialog(JFrame owner) {
        new LogsDialog(owner).setVisible(true);
    }

    private void reload() {
        if (loader != null) loader.cancel(true);
        refresh.setEnabled(false);
        loader = new SwingWorker<>() {
            @Override protected String doInBackground() throws IOException {
                return readTail(AppPaths.dataDirectory().resolve("logs/application.jsonl"), 200 * 1024);
            }

            @Override protected void done() {
                if (isCancelled()) return;
                refresh.setEnabled(true);
                try {
                    text.setText(get());
                    text.setCaretPosition(text.getDocument().getLength());
                    status.setText(AppLogs.error().isBlank() ? "只读视图 · 日志按大小轮转" : "日志写入失败，请检查目录权限");
                } catch (Exception exception) {
                    status.setText("无法读取日志，请检查数据目录权限。");
                }
            }
        };
        loader.execute();
    }

    static String readTail(Path file, int limit) throws IOException {
        if (!Files.exists(file)) return "尚无运行日志。开始对局或发起请求后，这里会显示记录。";
        if (limit <= 0) throw new IllegalArgumentException("日志读取上限必须大于 0");
        try (SeekableByteChannel channel = Files.newByteChannel(file)) {
            long offset = Math.max(0, channel.size() - limit);
            channel.position(offset);
            ByteBuffer buffer = ByteBuffer.allocate(limit);
            while (buffer.hasRemaining() && channel.read(buffer) != -1) { }
            buffer.flip();
            String result = StandardCharsets.UTF_8.decode(buffer).toString();
            if (offset > 0) {
                int newline = result.indexOf('\n');
                result = newline < 0 ? "" : result.substring(newline + 1);
            }
            return AppLogs.redact(result);
        }
    }

    private void openDirectory() {
        try {
            if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                throw new IOException("Desktop unavailable");
            }
            Desktop.getDesktop().open(AppPaths.dataDirectory().toFile());
        } catch (IOException | RuntimeException exception) {
            JOptionPane.showMessageDialog(this, "请在资源管理器中打开：\n" + AppPaths.dataDirectory(),
                    "数据目录", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    @Override public void dispose() {
        if (loader != null) loader.cancel(true);
        super.dispose();
    }
}
