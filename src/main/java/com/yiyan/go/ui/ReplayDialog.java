package com.yiyan.go.ui;

import com.yiyan.go.analysis.GameReview;
import com.yiyan.go.analysis.KataGoGameAnalyzer;
import com.yiyan.go.analysis.QuickGameReview;
import com.yiyan.go.diagnostics.AppLogs;
import com.yiyan.go.engine.EngineException;
import com.yiyan.go.engine.KataGoConfig;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.recording.GameArchive;
import com.yiyan.go.recording.GameRecord;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/** An independent, read-only board; navigating history never changes the live game. */
@SuppressWarnings("serial")
public final class ReplayDialog extends JDialog {
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());
    private final DefaultListModel<GameRecord> games = new DefaultListModel<>();
    private final JList<GameRecord> list = new JList<>(games);
    private final JLabel heading = new JLabel("把这一盘，再慢慢看一遍");
    private final JLabel summary = new JLabel("选择左侧对局，回看每一步。");
    private final JLabel status = new JLabel("正在读取对局…");
    private final JLabel analysisStatus = new JLabel("选择棋局后可开始分析");
    private final JLabel step = new JLabel("初始局面", SwingConstants.CENTER);
    private final JTextArea explanation = new JTextArea();
    private final JTextArea wholeGame = new JTextArea();
    private final JTabbedPane notesTabs = new JTabbedPane();
    private final JSlider slider = new JSlider(0, 0, 0);
    private final SoftButton first = new SoftButton("初始", SoftButton.Style.SECONDARY);
    private final SoftButton previous = new SoftButton("上一手", SoftButton.Style.SECONDARY);
    private final SoftButton next = new SoftButton("下一手", SoftButton.Style.SECONDARY);
    private final SoftButton last = new SoftButton("最后", SoftButton.Style.SECONDARY);
    private final SoftButton export = new SoftButton("导出 SGF", SoftButton.Style.PRIMARY);
    private final SoftButton refresh = new SoftButton("刷新记录", SoftButton.Style.GHOST);
    private final SoftButton analyze = new SoftButton("KataGo 分析整盘", SoftButton.Style.PRIMARY);
    private BoardState replayState = new BoardState();
    private GameRecord selected;
    private GameReview activeReview;
    private final Map<String, GameReview> reviewCache = new HashMap<>();
    private final BoardPanel board = new BoardPanel(() -> replayState, () -> false, (x, y) -> { });
    private SwingWorker<GameArchive.ScanResult, Void> loader;
    private SwingWorker<GameReview, Void> analysisWorker;

    private ReplayDialog(JFrame owner) {
        super(owner, "复盘与对局记录 · 弈言", true);
        setDefaultCloseOperation(DISPOSE_ON_CLOSE);
        setSize(1260, 820);
        setMinimumSize(new Dimension(1060, 720));
        setLocationRelativeTo(owner);
        JPanel root = new JPanel(new BorderLayout(20, 16));
        root.setBackground(Theme.CANVAS);
        root.setBorder(BorderFactory.createEmptyBorder(24, 24, 20, 24));
        JPanel navigation = new JPanel(new BorderLayout(0, 12));
        navigation.setOpaque(false);
        navigation.setPreferredSize(new Dimension(240, 650));
        JLabel history = new JLabel("对局留存");
        history.setFont(Theme.display(22));
        navigation.add(history, BorderLayout.NORTH);
        list.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        list.setBackground(Theme.SIDEBAR);
        list.setFixedCellHeight(92);
        list.setCellRenderer((values, game, index, chosen, focus) -> historyRow(game, chosen));
        list.getAccessibleContext().setAccessibleName("历史对局列表");
        list.addListSelectionListener(event -> { if (!event.getValueIsAdjusting()) selectGame(list.getSelectedValue()); });
        JScrollPane historyScroll = new JScrollPane(list);
        historyScroll.setBorder(BorderFactory.createLineBorder(Theme.BORDER_SOFT));
        navigation.add(historyScroll, BorderLayout.CENTER);
        refresh.addActionListener(event -> reload());
        navigation.add(refresh, BorderLayout.SOUTH);
        root.add(navigation, BorderLayout.WEST);

        JPanel workspace = new JPanel(new BorderLayout(16, 16));
        workspace.setOpaque(false);
        JPanel titles = new JPanel(new GridLayout(2, 1, 0, 7));
        titles.setOpaque(false);
        heading.setFont(Theme.display(23));
        summary.setFont(Theme.body(12));
        summary.setForeground(Theme.TEXT_MUTED);
        titles.add(heading);
        titles.add(summary);
        workspace.add(titles, BorderLayout.NORTH);
        RoundedPanel paper = new RoundedPanel(Theme.SURFACE, Theme.BORDER_SOFT, 16);
        paper.setLayout(new BorderLayout(10, 0));
        paper.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        paper.add(board, BorderLayout.CENTER);
        explanation.setEditable(false);
        explanation.setLineWrap(true);
        explanation.setWrapStyleWord(true);
        explanation.setFont(Theme.body(13));
        explanation.setForeground(Theme.TEXT);
        explanation.setBackground(Theme.SURFACE);
        explanation.setMargin(new Insets(12, 10, 12, 10));
        explanation.getAccessibleContext().setAccessibleName("当前手说明与结果");
        JScrollPane notes = new JScrollPane(explanation);
        notes.setBorder(BorderFactory.createEmptyBorder());

        wholeGame.setEditable(false);
        wholeGame.setLineWrap(true);
        wholeGame.setWrapStyleWord(true);
        wholeGame.setFont(Theme.body(13));
        wholeGame.setForeground(Theme.TEXT);
        wholeGame.setBackground(Theme.SURFACE);
        wholeGame.setMargin(new Insets(12, 10, 12, 10));
        wholeGame.getAccessibleContext().setAccessibleName("整盘棋分析与训练建议");
        JScrollPane reviewScroll = new JScrollPane(wholeGame);
        reviewScroll.setBorder(BorderFactory.createEmptyBorder());
        JPanel review = new JPanel(new BorderLayout(0, 9));
        review.setBackground(Theme.SURFACE);
        JPanel reviewTools = new JPanel(new BorderLayout(8, 5));
        reviewTools.setBackground(Theme.SURFACE);
        analyze.addActionListener(event -> analyzeWholeGame());
        reviewTools.add(analyze, BorderLayout.NORTH);
        analysisStatus.setFont(Theme.body(10));
        analysisStatus.setForeground(Theme.TEXT_MUTED);
        analysisStatus.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 2));
        reviewTools.add(analysisStatus, BorderLayout.SOUTH);
        review.add(reviewTools, BorderLayout.NORTH);
        review.add(reviewScroll, BorderLayout.CENTER);

        notesTabs.addTab("当前手", notes);
        notesTabs.addTab("整盘分析", review);
        notesTabs.setSelectedIndex(1);
        notesTabs.setPreferredSize(new Dimension(350, 450));
        notesTabs.setFont(Theme.bodyMedium(12));
        notesTabs.setBorder(BorderFactory.createMatteBorder(0, 1, 0, 0, Theme.BORDER_SOFT));
        notesTabs.getAccessibleContext().setAccessibleName("复盘说明");
        paper.add(notesTabs, BorderLayout.EAST);
        workspace.add(paper, BorderLayout.CENTER);

        JPanel timeline = new JPanel(new BorderLayout(0, 10));
        timeline.setOpaque(false);
        slider.setOpaque(false);
        slider.getAccessibleContext().setAccessibleName("复盘手数");
        slider.addChangeListener(event -> renderPosition());
        timeline.add(slider, BorderLayout.NORTH);
        JPanel controls = new JPanel(new BorderLayout(10, 0));
        controls.setOpaque(false);
        JPanel arrows = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
        arrows.setOpaque(false);
        first.addActionListener(event -> slider.setValue(0));
        previous.addActionListener(event -> slider.setValue(slider.getValue() - 1));
        next.addActionListener(event -> slider.setValue(slider.getValue() + 1));
        last.addActionListener(event -> slider.setValue(slider.getMaximum()));
        arrows.add(first); arrows.add(previous); arrows.add(next); arrows.add(last);
        controls.add(arrows, BorderLayout.WEST);
        step.setFont(Theme.bodyMedium(12));
        controls.add(step, BorderLayout.CENTER);
        export.addActionListener(event -> exportSgf());
        controls.add(export, BorderLayout.EAST);
        timeline.add(controls, BorderLayout.CENTER);
        workspace.add(timeline, BorderLayout.SOUTH);
        root.add(workspace, BorderLayout.CENTER);
        status.setFont(Theme.body(11));
        status.setForeground(Theme.TEXT_MUTED);
        root.add(status, BorderLayout.SOUTH);
        setContentPane(root);
        getRootPane().registerKeyboardAction(event -> dispose(), KeyStroke.getKeyStroke("ESCAPE"), JComponent.WHEN_IN_FOCUSED_WINDOW);
        selectGame(null);
        reload();
    }

    public static void showDialog(JFrame owner) { new ReplayDialog(owner).setVisible(true); }

    private JPanel historyRow(GameRecord game, boolean chosen) {
        JPanel row = new JPanel(new GridLayout(3, 1, 0, 4));
        row.setBackground(chosen ? Theme.ACCENT_SOFT : Theme.SIDEBAR);
        row.setBorder(BorderFactory.createEmptyBorder(12, 13, 12, 10));
        JLabel when = new JLabel(DATE.format(game.started()));
        when.setFont(Theme.bodyMedium(12));
        JLabel detail = new JLabel(game.size() + " 路 · 你执" + game.humanColor().chineseName() + " · " + game.moves().size() + " 手");
        detail.setFont(Theme.body(11));
        JLabel result = new JLabel(game.status().label());
        result.setFont(Theme.body(10));
        result.setForeground(Theme.ACCENT_DARK);
        row.add(when); row.add(detail); row.add(result);
        return row;
    }

    private void reload() {
        if (loader != null) loader.cancel(true);
        String selectedId = selected == null ? null : selected.id();
        refresh.setEnabled(false);
        loader = new SwingWorker<>() {
            @Override protected GameArchive.ScanResult doInBackground() { return GameArchive.list(); }
            @Override protected void done() {
                if (isCancelled()) return;
                refresh.setEnabled(true);
                try {
                    var result = get();
                    games.clear();
                    int index = 0;
                    for (var game : result.games()) {
                        if (game.id().equals(selectedId)) index = games.size();
                        games.addElement(game);
                    }
                    if (!games.isEmpty()) list.setSelectedIndex(index);
                    String message = result.warnings().isEmpty()
                            ? games.size() + " 局记录 · 只读复盘，不影响正在进行的棋局"
                            : String.join("；", result.warnings());
                    status.setText(message.length() > 100 ? message.substring(0, 100) + "…" : message);
                    status.setToolTipText(message);
                    status.setForeground(result.warnings().isEmpty() ? Theme.TEXT_MUTED : Theme.DANGER);
                } catch (Exception exception) {
                    status.setText("记录加载失败，请检查数据目录权限。");
                }
            }
        };
        loader.execute();
    }

    private void selectGame(GameRecord game) {
        cancelAnalysis("selection_changed");
        selected = game;
        activeReview = game == null ? null : reviewCache.get(game.id());
        slider.setMaximum(game == null ? 0 : game.moves().size());
        slider.setValue(slider.getMaximum());
        slider.setEnabled(game != null && !game.moves().isEmpty());
        export.setEnabled(game != null);
        analyze.setEnabled(game != null && !game.moves().isEmpty());
        analyze.setText(activeReview == null ? "KataGo 分析整盘" : "重新分析");
        analysisStatus.setText(game == null ? "选择棋局后可开始分析"
                : activeReview == null ? "尚未运行引擎分析" : "本次窗口内已完成分析");
        wholeGame.setText(game == null ? QuickGameReview.format(null)
                : activeReview == null ? QuickGameReview.format(game) : activeReview.formatted());
        wholeGame.setCaretPosition(0);
        renderPosition();
    }

    private void renderPosition() {
        board.setScoreMarks(java.util.Set.of(), java.util.Set.of());
        int ply = slider.getValue();
        first.setEnabled(selected != null && ply > 0);
        previous.setEnabled(selected != null && ply > 0);
        next.setEnabled(selected != null && ply < slider.getMaximum());
        last.setEnabled(selected != null && ply < slider.getMaximum());
        if (selected == null) {
            replayState = new BoardState();
            heading.setText("把这一盘，再慢慢看一遍");
            summary.setText("开始一盘新棋后，这里会自动留存记录。");
            explanation.setText("选择左侧对局，就能回看每一步的盘面、实际来源和落子说明。\n\n旧版本没有保存的棋局无法补回。");
            step.setText("初始局面");
        } else {
            ply = Math.min(ply, selected.moves().size());
            replayState = ply == selected.moves().size() ? selected.finalPosition() : selected.positionAt(ply);
            heading.setText(DATE.format(selected.started()) + " 的棋局");
            summary.setText(selected.size() + " 路 · 贴 " + selected.komi() + " 目 · " + selected.status().label());
            step.setText(ply + " / " + selected.moves().size() + " 手");
            String detail = "初始局面\n黑棋先行。";
            if (ply > 0) {
                var move = selected.moves().get(ply - 1);
                detail = move.move().stone().chineseName() + " · 第 " + ply + " 手 · " + move.move().coordinate(selected.size())
                        + "\n\n来源：" + move.source() + "\n耗时：" + move.elapsedMs() + " ms"
                        + "\n提子：" + move.captured().size() + " 子\n\n"
                        + (move.motivation().isBlank() ? "" : ExplanationCopy.forReplay(move.motivation(), move.move().pass()));
            }
            if (ply == selected.moves().size() && !selected.result().isBlank()) detail += "\n\n—— 对局结果 ——\n" + selected.result();
            if (ply == selected.moves().size() && selected.finalScore() != null) {
                board.setScoreMarks(selected.finalScore().deadStones(), selected.finalScore().neutralPoints());
                detail += "\n\n红叉：已确认死子；灰框：中立区域标记。原始棋盘保留，计算时移除死子。";
            }
            if (ply > 0 && activeReview != null) {
                String engineDetail = activeReview.detailForMove(ply);
                if (!engineDetail.isBlank()) detail += "\n\n—— KataGo 对本手的判断 ——\n" + engineDetail;
            }
            explanation.setText(AppLogs.redact(detail));
            explanation.setCaretPosition(0);
        }
        board.repaint();
    }

    private void analyzeWholeGame() {
        if (selected == null || selected.moves().isEmpty()) return;
        cancelAnalysis("restarted");
        GameRecord game = selected;
        notesTabs.setSelectedIndex(1);
        analyze.setEnabled(false);
        analyze.setText("正在分析…");
        analysisStatus.setText("KataGo 正在逐手检查整盘棋，请稍候…");
        wholeGame.setText(QuickGameReview.format(game) + "\n\n正在计算胜负走势和关键转折…");
        wholeGame.setCaretPosition(0);
        AppLogs.event("review", "analysis_started", Map.of("gameId", game.id(),
                "count", game.moves().size(), "boardSize", game.size()));
        analysisWorker = new SwingWorker<>() {
            @Override protected GameReview doInBackground() throws Exception {
                try {
                    return new KataGoGameAnalyzer(KataGoConfig.discover()).analyze(game);
                } catch (java.io.IOException exception) {
                    if (exception instanceof EngineException) throw exception;
                    throw new EngineException("未找到完整的 KataGo 引擎，无法进行整盘分析", exception);
                }
            }

            @Override protected void done() {
                if (analysisWorker == this) analysisWorker = null;
                if (isCancelled() || selected == null || !selected.id().equals(game.id())) return;
                analyze.setEnabled(true);
                try {
                    GameReview result = get();
                    activeReview = result;
                    reviewCache.put(game.id(), result);
                    wholeGame.setText(AppLogs.redact(result.formatted()));
                    wholeGame.setCaretPosition(0);
                    analysisStatus.setText("分析完成 · 可在“当前手”中逐手查看");
                    analyze.setText("重新分析");
                    renderPosition();
                    AppLogs.event("review", "analysis_completed", Map.of("gameId", game.id(),
                            "count", game.moves().size(), "durationMs", result.elapsedMs(),
                            "maxVisits", result.visitsPerPosition(),
                            "humanMistakes", result.humanMistakes().size(),
                            "opportunities", result.opportunities().size()));
                } catch (java.util.concurrent.CancellationException ignored) {
                    analyze.setText("KataGo 分析整盘");
                } catch (Exception exception) {
                    Throwable cause = AppLogs.unwrap(exception);
                    String message = AppLogs.safeError(cause);
                    analysisStatus.setText("分析未完成：" + message);
                    analyze.setText("重新分析");
                    wholeGame.setText(QuickGameReview.format(game) + "\n\n分析未完成：" + message
                            + "\n可检查 KataGo 文件后重试；棋谱和基础概览仍可正常使用。");
                    wholeGame.setCaretPosition(0);
                    AppLogs.event("review", "analysis_failed", Map.of("gameId", game.id(),
                            "count", game.moves().size(), "errorCode", AppLogs.errorCode(cause),
                            "errorClass", cause.getClass().getSimpleName()));
                }
            }
        };
        analysisWorker.execute();
    }

    private void cancelAnalysis(String reason) {
        SwingWorker<GameReview, Void> worker = analysisWorker;
        if (worker == null || worker.isDone()) return;
        worker.cancel(true);
        analysisWorker = null;
        AppLogs.event("review", "analysis_cancelled", Map.of(
                "gameId", selected == null ? "" : selected.id(), "reason", reason));
    }

    private void exportSgf() {
        if (selected == null) return;
        GameRecord game = selected;
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出棋谱（SGF）");
        chooser.setFileFilter(new FileNameExtensionFilter("SGF 围棋棋谱", "sgf"));
        chooser.setSelectedFile(new java.io.File("弈言-" + game.id().substring(0, 8) + ".sgf"));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        if (!path.getFileName().toString().toLowerCase(java.util.Locale.ROOT).endsWith(".sgf")) {
            path = path.resolveSibling(path.getFileName() + ".sgf");
        }
        if (Files.exists(path) && JOptionPane.showConfirmDialog(this, "这个文件已经存在，确认替换吗？", "替换棋谱",
                JOptionPane.OK_CANCEL_OPTION) != JOptionPane.OK_OPTION) return;
        try {
            GameArchive.exportSgf(game, path);
            status.setText("棋谱已导出：" + path.getFileName());
            status.setToolTipText(path.toAbsolutePath().toString());
            AppLogs.event("game", "sgf_exported", Map.of("gameId", game.id(), "count", game.moves().size()));
        } catch (Exception exception) {
            JOptionPane.showMessageDialog(this, "棋谱没有写入成功，请检查文件权限和磁盘空间。", "导出未完成", JOptionPane.WARNING_MESSAGE);
        }
    }

    @Override public void dispose() {
        if (loader != null) loader.cancel(true);
        cancelAnalysis("dialog_closed");
        super.dispose();
    }
}
