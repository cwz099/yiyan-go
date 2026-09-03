package com.yiyan.go.ui;

import com.yiyan.go.ai.AiDecision;
import com.yiyan.go.ai.DeepSeekConfig;
import com.yiyan.go.ai.DeepSeekOpponent;
import com.yiyan.go.ai.GoOpponent;
import com.yiyan.go.ai.LocalGoOpponent;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.GameSettings;
import com.yiyan.go.game.Move;
import com.yiyan.go.game.MoveResult;
import com.yiyan.go.game.FinalScore;
import com.yiyan.go.game.Stone;
import com.yiyan.go.diagnostics.AppLogs;
import com.yiyan.go.diagnostics.AppPaths;
import com.yiyan.go.recording.GameRecorder;
import com.yiyan.go.engine.EngineVerdict;
import com.yiyan.go.engine.KataGoConfig;
import com.yiyan.go.engine.KataGoEngine;
import com.yiyan.go.engine.ScoreEngine;

import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.io.IOException;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;

@SuppressWarnings("serial")
public final class MainFrame extends JFrame {
    private enum MessageKind {WELCOME, PLAYER, AI, SYSTEM, WARNING, FINISH}

    private record Narrative(int moveNumber, MessageKind kind, String meta, String text) {
    }

    private GameSettings settings = GameSettings.load(AppPaths.dataDirectory().resolve("settings.xml"));
    private BoardState state = new BoardState(settings.boardSize(), settings.komi());
    private GameRecorder recorder;
    private final Deque<BoardState> undoStack = new ArrayDeque<>();
    private final List<Narrative> narratives = new ArrayList<>();
    private final LocalGoOpponent localOpponent = new LocalGoOpponent();
    private GoOpponent opponent = localOpponent;
    private DeepSeekConfig deepSeekConfig;
    private String lastAiStatus = "离线";
    private String resultSummary = "";
    private FinalScore confirmedScore;
    private long humanTurnStarted = System.nanoTime();
    private SwingWorker<AiDecision, Void> aiWorker;
    private boolean aiThinking;
    private long revision;
    private int hintRevision;
    private ScoreEngine scoreEngine;
    private SwingWorker<EngineVerdict, Void> scoreWorker;
    private boolean scoring;
    private EngineVerdict engineVerdict;
    private final JLabel engineLabel = new JLabel("判定 · 手动数子");

    private final JLabel turnLabel = new JLabel();
    private final JLabel statusDetailLabel = new JLabel();
    private final JLabel captureLabel = new JLabel();
    private final JLabel hintLabel = new JLabel();
    private final JLabel subtitleLabel = new JLabel();
    private final JLabel recentTitle = new JLabel();
    private final JLabel recentSubtitle = new JLabel();
    private final SoftButton providerButton = new SoftButton("本地陪练", SoftButton.Style.SECONDARY);
    private final SoftButton navProviderButton = new SoftButton("本地陪练 · 离线", SoftButton.Style.SECONDARY);
    private final SoftButton undoButton = new SoftButton("悔棋", SoftButton.Style.SECONDARY);
    private final SoftButton passButton = new SoftButton("停一手", SoftButton.Style.PRIMARY);
    private final SoftButton resignButton = new SoftButton("认输", SoftButton.Style.GHOST);
    private final SoftButton retryButton = new SoftButton("重试 AI", SoftButton.Style.SECONDARY);
    private final SoftButton scoringButton = new SoftButton("终局判定", SoftButton.Style.PRIMARY);
    private final SoftButton reviewScoreButton = new SoftButton("查看计分", SoftButton.Style.SECONDARY);
    private final JPanel messageList = new TranscriptPanel();
    private final JScrollPane messageScroll;
    private final BoardPanel boardPanel;

    public MainFrame() {
        super("弈言 · 会解释的围棋对手");
        setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        setMinimumSize(new Dimension(1060, 720));
        setSize(new Dimension(1420, 880));
        setLocationRelativeTo(null);
        setBackground(Theme.CANVAS);

        boardPanel = new BoardPanel(() -> state, this::canHumanPlay, this::handleHumanPlay);
        messageList.setOpaque(false);
        messageScroll = new JScrollPane(messageList,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        messageScroll.setOpaque(false);
        messageScroll.getViewport().setOpaque(false);
        messageScroll.setBorder(BorderFactory.createEmptyBorder());
        messageScroll.getVerticalScrollBar().setUnitIncrement(14);
        messageScroll.getViewport().addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) { messageList.revalidate(); }
        });
        messageList.getAccessibleContext().setAccessibleName("AI 落子说明列表");

        setContentPane(createRoot());
        installActions();
        recorder = GameRecorder.start(state, settings.humanColor(), opponent.displayName());
        resetNarratives();
        refreshAll();
        reportStorageFailure();

        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent event) {
                invalidateAiRequest();
                recorder.pause(state);
                AppLogs.event("app", "closed", Map.of("gameId", recorder.id()));
            }
        });
        SwingUtilities.invokeLater(() -> {
            if (isDisplayable()) startAiTurn(false);
        });
    }

    private JPanel createRoot() {
        JPanel root = new JPanel(new BorderLayout());
        root.setBackground(Theme.CANVAS);
        root.add(createNavigation(), BorderLayout.WEST);

        JPanel main = new JPanel(new BorderLayout());
        main.setOpaque(false);
        main.add(createHeader(), BorderLayout.NORTH);
        main.add(createWorkspace(), BorderLayout.CENTER);
        root.add(main, BorderLayout.CENTER);
        return root;
    }

    private JComponent createNavigation() {
        JPanel navigation = new JPanel();
        navigation.setBackground(Theme.SIDEBAR);
        navigation.setPreferredSize(new Dimension(210, 720));
        navigation.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(0, 0, 0, 1, Theme.BORDER_SOFT),
                BorderFactory.createEmptyBorder(25, 18, 20, 18)));
        navigation.setLayout(new BoxLayout(navigation, BoxLayout.Y_AXIS));

        JPanel brand = new JPanel(new FlowLayout(FlowLayout.LEFT, 9, 0));
        brand.setOpaque(false);
        brand.setAlignmentX(Component.LEFT_ALIGNMENT);
        brand.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        brand.add(new LogoMark());
        JPanel brandText = new JPanel();
        brandText.setOpaque(false);
        brandText.setLayout(new BoxLayout(brandText, BoxLayout.Y_AXIS));
        JLabel name = new JLabel("弈言");
        name.setFont(Theme.display(21));
        JLabel byline = new JLabel("一盘安静的棋");
        byline.setFont(Theme.body(10));
        byline.setForeground(Theme.TEXT_MUTED);
        brandText.add(name);
        brandText.add(byline);
        brand.add(brandText);

        SoftButton newGame = new SoftButton("＋  新棋局", SoftButton.Style.PRIMARY);
        newGame.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        newGame.setAlignmentX(Component.LEFT_ALIGNMENT);
        newGame.addActionListener(event -> newGame());

        navigation.add(brand);
        navigation.add(Box.createVerticalStrut(27));
        navigation.add(newGame);
        navigation.add(Box.createVerticalStrut(28));
        navigation.add(sectionLabel("棋局"));
        navigation.add(Box.createVerticalStrut(7));
        navigation.add(navItem("●", "正在对弈", true));
        navigation.add(Box.createVerticalStrut(4));
        navigation.add(navigationButton("复盘与对局记录", () -> ReplayDialog.showDialog(this)));
        navigation.add(Box.createVerticalStrut(4));
        navigation.add(navigationButton("对局设置", this::showGameSettings));
        navigation.add(Box.createVerticalStrut(4));
        navigation.add(navigationButton("运行与网络日志", () -> LogsDialog.showDialog(this)));
        navigation.add(Box.createVerticalStrut(26));
        navigation.add(sectionLabel("最近"));
        navigation.add(Box.createVerticalStrut(9));
        navigation.add(recentGameCard());
        navigation.add(Box.createVerticalGlue());
        engineLabel.setFont(Theme.body(11));
        engineLabel.setForeground(Theme.TEXT_MUTED);
        engineLabel.setAlignmentX(Component.LEFT_ALIGNMENT);
        navigation.add(engineLabel);
        navigation.add(Box.createVerticalStrut(12));

        navProviderButton.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
        navProviderButton.setAlignmentX(Component.LEFT_ALIGNMENT);
        navProviderButton.setToolTipText("连接或更换 AI 对手");
        navProviderButton.getAccessibleContext().setAccessibleDescription("连接或更换 AI 对手");
        navProviderButton.addActionListener(event -> showConnectionDialog());
        navigation.add(navProviderButton);
        return navigation;
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(20, 0));
        header.setOpaque(false);
        header.setBorder(BorderFactory.createEmptyBorder(22, 28, 18, 28));
        header.setPreferredSize(new Dimension(900, 88));

        JPanel copy = new JPanel();
        copy.setOpaque(false);
        copy.setLayout(new BoxLayout(copy, BoxLayout.Y_AXIS));
        JLabel title = new JLabel("和一位会解释的对手下棋");
        title.setFont(Theme.display(22));
        title.setForeground(Theme.TEXT);
        subtitleLabel.setFont(Theme.body(11));
        subtitleLabel.setForeground(Theme.TEXT_MUTED);
        copy.add(title);
        copy.add(Box.createVerticalStrut(5));
        copy.add(subtitleLabel);

        providerButton.addActionListener(event -> showConnectionDialog());
        providerButton.setToolTipText("连接或更换 AI 对手");
        providerButton.getAccessibleContext().setAccessibleDescription("连接或更换 AI 对手");
        header.add(copy, BorderLayout.WEST);
        header.add(providerButton, BorderLayout.EAST);
        return header;
    }

    private JPanel createWorkspace() {
        JPanel workspace = new JPanel(new BorderLayout(18, 0));
        workspace.setOpaque(false);
        workspace.setBorder(BorderFactory.createEmptyBorder(0, 26, 25, 26));
        workspace.add(createBoardCard(), BorderLayout.CENTER);
        JComponent conversation = createConversationPanel();
        workspace.add(conversation, BorderLayout.EAST);
        workspace.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override public void componentResized(java.awt.event.ComponentEvent event) {
                int width = Math.max(300, Math.min(410, Math.round(workspace.getWidth() * 0.34f)));
                if (conversation.getPreferredSize().width != width) {
                    conversation.setPreferredSize(new Dimension(width, 700));
                    workspace.revalidate();
                }
            }
        });
        return workspace;
    }

    private JComponent createBoardCard() {
        RoundedPanel card = new RoundedPanel(Theme.SURFACE, Theme.BORDER_SOFT, 18);
        card.setLayout(new BorderLayout());
        card.setBorder(BorderFactory.createEmptyBorder(18, 21, 15, 21));

        JPanel status = new JPanel(new BorderLayout());
        status.setOpaque(false);
        status.setBorder(BorderFactory.createEmptyBorder(0, 2, 12, 2));
        JPanel statusCopy = new JPanel();
        statusCopy.setOpaque(false);
        statusCopy.setLayout(new BoxLayout(statusCopy, BoxLayout.Y_AXIS));
        turnLabel.setFont(Theme.bodyMedium(14));
        statusDetailLabel.setFont(Theme.body(11));
        statusDetailLabel.setForeground(Theme.TEXT_MUTED);
        statusCopy.add(turnLabel);
        statusCopy.add(Box.createVerticalStrut(3));
        statusCopy.add(statusDetailLabel);
        captureLabel.setFont(Theme.body(11));
        captureLabel.setForeground(Theme.TEXT_SECONDARY);
        status.add(statusCopy, BorderLayout.WEST);
        status.add(captureLabel, BorderLayout.EAST);

        JPanel boardHolder = new JPanel(new GridBagLayout());
        boardHolder.setOpaque(false);
        GridBagConstraints boardConstraints = new GridBagConstraints();
        boardConstraints.gridx = 0;
        boardConstraints.gridy = 0;
        boardConstraints.weightx = 1;
        boardConstraints.weighty = 1;
        boardConstraints.fill = GridBagConstraints.BOTH;
        boardHolder.add(boardPanel, boardConstraints);

        JPanel footer = new JPanel(new BorderLayout(0, 8));
        footer.setOpaque(false);
        footer.setBorder(BorderFactory.createEmptyBorder(12, 2, 0, 2));
        hintLabel.setFont(Theme.body(11));
        hintLabel.setForeground(Theme.TEXT_MUTED);
        JPanel actions = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        actions.setOpaque(false);
        actions.add(retryButton);
        actions.add(resignButton);
        actions.add(undoButton);
        actions.add(passButton);
        actions.add(scoringButton);
        actions.add(reviewScoreButton);
        footer.add(hintLabel, BorderLayout.SOUTH);
        footer.add(actions, BorderLayout.NORTH);

        card.add(status, BorderLayout.NORTH);
        card.add(boardHolder, BorderLayout.CENTER);
        card.add(footer, BorderLayout.SOUTH);
        return card;
    }

    private JComponent createConversationPanel() {
        RoundedPanel panel = new RoundedPanel(Theme.SURFACE, Theme.BORDER_SOFT, 18);
        panel.setPreferredSize(new Dimension(390, 700));
        panel.setMinimumSize(new Dimension(300, 400));
        panel.setLayout(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(21, 20, 17, 20));

        JPanel heading = new JPanel();
        heading.setOpaque(false);
        heading.setLayout(new BoxLayout(heading, BoxLayout.Y_AXIS));
        JLabel eyebrow = new JLabel("棋局札记");
        eyebrow.setFont(Theme.bodyMedium(11));
        eyebrow.setForeground(Theme.ACCENT_DARK);
        JLabel title = new JLabel("落子说明");
        title.setFont(Theme.display(22));
        title.setForeground(Theme.TEXT);
        heading.add(eyebrow);
        heading.add(Box.createVerticalStrut(6));
        heading.add(title);
        heading.setBorder(BorderFactory.createEmptyBorder(0, 2, 18, 2));

        panel.add(heading, BorderLayout.NORTH);
        panel.add(messageScroll, BorderLayout.CENTER);
        return panel;
    }

    private void installActions() {
        undoButton.addActionListener(event -> undoTurn());
        passButton.addActionListener(event -> handleHumanPass());
        retryButton.addActionListener(event -> startAiTurn(false));
        resignButton.addActionListener(event -> resign());
        scoringButton.addActionListener(event -> showScoring());
        reviewScoreButton.addActionListener(event -> reviewScore());
    }

    private void handleHumanPlay(int x, int y) {
        if (!canHumanPlay()) {
            showHint(aiThinking ? "我还在看这一手，请稍等。" : "现在还不能在这里落子。", Theme.WARNING);
            return;
        }
        BoardState before = state.copy();
        MoveResult result = state.play(x, y);
        if (!result.success()) {
            recorder.event("illegal_move", "坐标 " + x + "," + y + "：" + result.message(), state);
            Toolkit.getDefaultToolkit().beep();
            showHint(result.message(), Theme.DANGER);
            boardPanel.repaint();
            return;
        }
        undoStack.addLast(before);
        revision++;
        Move move = result.move();
        recorder.record(state, result, "human", "", humanElapsedMs());
        narratives.add(new Narrative(move.number(), MessageKind.PLAYER,
                "你 · 第 " + move.number() + " 手 · " + move.coordinate(state.size()), ""));
        refreshAll();
        startAiTurn(false);
    }

    private void handleHumanPass() {
        if (!canHumanPlay()) {
            showHint(aiThinking ? "我还在看这一手，请稍等。" : "现在不能停一手。", Theme.WARNING);
            return;
        }
        BoardState before = state.copy();
        MoveResult result = state.pass();
        if (!result.success()) return;
        undoStack.addLast(before);
        revision++;
        Move move = result.move();
        recorder.record(state, result, "human", "", humanElapsedMs());
        narratives.add(new Narrative(move.number(), MessageKind.PLAYER,
                "你 · 第 " + move.number() + " 手 · 停一手", ""));
        refreshAll();
        if (state.gameOver()) finishGame();
        else startAiTurn(false);
    }

    private void startAiTurn(boolean fallback) {
        if (aiThinking || state.gameOver() || state.turn() == settings.humanColor()) return;
        aiThinking = true;
        lastAiStatus = fallback ? "本地接续中" : opponent == localOpponent ? "本地计算中" : "请求中 · 核验与有限纠错";
        refreshAll();
        rebuildMessages();

        final long expectedRevision = revision;
        final BoardState requestedPosition = state.copy();
        final GoOpponent turnOpponent = fallback ? localOpponent : opponent;
        final long started = System.nanoTime();
        AppLogs.event("game", "ai_turn_started", Map.of("gameId", recorder.id(),
                "moveNumber", state.moveNumber() + 1, "source", lastAiStatus));
        aiWorker = new SwingWorker<>() {
            @Override
            protected AiDecision doInBackground() throws Exception {
                return turnOpponent.chooseMove(requestedPosition);
            }

            @Override
            protected void done() {
                if (expectedRevision != revision || isCancelled()) return;
                aiThinking = false;
                try {
                    AiDecision decision = get();
                    applyAiDecision(decision, fallback, (System.nanoTime() - started) / 1_000_000);
                } catch (CancellationException ignored) {
                    // A new game or undo invalidated this request.
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    handleAiFailure(exception, fallback);
                } catch (ExecutionException | RuntimeException exception) {
                    Throwable cause = exception instanceof ExecutionException && exception.getCause() != null
                            ? exception.getCause() : exception;
                    handleAiFailure(cause, fallback);
                }
            }
        };
        aiWorker.execute();
    }

    private void applyAiDecision(AiDecision decision, boolean fallback, long elapsedMs) {
        BoardState before = state.copy();
        MoveResult result = decision.pass()
                ? state.pass()
                : state.play(decision.point().x(), decision.point().y());
        if (!result.success()) {
            handleAiFailure(new IllegalStateException(result.message()), fallback);
            return;
        }
        undoStack.addLast(before);
        revision++;
        Move move = result.move();
        String source = fallback && opponent != localOpponent ? "本地接续"
                : opponent == localOpponent ? "本地陪练" : opponent.displayName();
        if (!fallback && opponent != localOpponent && decision.attempts() > 1) source += " · 第 " + decision.attempts() + " 次请求成功";
        lastAiStatus = fallback ? "本手本地接续" : opponent == localOpponent ? "离线" : "最近请求成功";
        String explanation = AppLogs.redact(decision.motivation());
        recorder.record(state, result, source, explanation, elapsedMs);
        humanTurnStarted = System.nanoTime();
        AppLogs.event("game", "ai_move_applied", Map.of("gameId", recorder.id(),
                "moveNumber", move.number(), "source", source, "action", move.pass() ? "PASS" : "PLAY",
                "durationMs", elapsedMs, "requestId", decision.requestId(), "attempts", decision.attempts()));
        narratives.add(new Narrative(move.number(), MessageKind.AI,
                move.stone().chineseName() + " · 第 " + move.number() + " 手 · "
                        + move.coordinate(state.size()) + (fallback ? " · 本地接续" : ""), explanation));
        refreshAll();
        rebuildMessages();
        if (state.gameOver()) finishGame();
    }

    private void handleAiFailure(Throwable error, boolean fallback) {
        aiThinking = false;
        String reason = AppLogs.safeError(error);
        lastAiStatus = "最近请求失败";
        recorder.event("ai_failure", reason, state);
        AppLogs.event("game", "ai_turn_failed", Map.of("gameId", recorder.id(),
                "moveNumber", state.moveNumber() + 1, "reason", reason,
                "errorClass", error.getClass().getSimpleName()));
        if (!fallback && opponent != localOpponent && settings.autoFallback()) {
            narratives.add(new Narrative(state.moveNumber(), MessageKind.WARNING,
                    "本手改由本地接续", reason + "。本手由本地陪练完成；下一回合仍会尝试 DeepSeek。"));
            recorder.event("fallback", "DeepSeek → 本地陪练：" + reason, state);
            rebuildMessages();
            startAiTurn(true);
        } else {
            narratives.add(new Narrative(state.moveNumber(), MessageKind.WARNING,
                    "AI 暂停落子", reason + "。可重试或更换对手。"));
            refreshAll();
            rebuildMessages();
        }
    }

    private void undoTurn() {
        if (!settings.allowUndo()) {
            showHint("这盘棋的设置不允许悔棋。", Theme.TEXT_MUTED);
            return;
        }
        if (undoStack.isEmpty()) {
            showHint("现在还没有可以撤回的着手。", Theme.TEXT_MUTED);
            return;
        }
        invalidateAiRequest();
        do {
            state = undoStack.removeLast();
        } while (state.turn() != settings.humanColor() && !undoStack.isEmpty());
        resultSummary = "";
        confirmedScore = null;
        recorder.undo(state);
        humanTurnStarted = System.nanoTime();
        AppLogs.event("game", "undo", Map.of("gameId", recorder.id(), "moveNumber", state.moveNumber()));
        narratives.removeIf(message -> message.kind() == MessageKind.FINISH || message.moveNumber() > state.moveNumber());
        if (narratives.isEmpty()) resetNarratives();
        showHint("已经回到你上一次思考之前。", Theme.SUCCESS);
        refreshAll();
        rebuildMessages();
        startAiTurn(false);
    }

    private void newGame() {
        if (!confirmNewGame()) return;
        beginNewGame();
    }

    private boolean confirmNewGame() {
        return state.moveNumber() == 0 || state.gameOver() || JOptionPane.showConfirmDialog(this,
                "当前对局会保存在复盘记录中。现在开始新棋局吗？", "留存这盘棋",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) == JOptionPane.OK_OPTION;
    }

    private void beginNewGame() {
        invalidateAiRequest();
        recorder.pause(state);
        state = new BoardState(settings.boardSize(), settings.komi());
        resultSummary = "";
        confirmedScore = null;
        undoStack.clear();
        humanTurnStarted = System.nanoTime();
        recorder = GameRecorder.start(state, settings.humanColor(), opponent.displayName());
        if (deepSeekConfig != null) opponent = new DeepSeekOpponent(deepSeekConfig, recorder.id());
        lastAiStatus = opponent == localOpponent ? "离线" : "待请求";
        resetNarratives();
        showHint("新棋局已经摆好。你执" + settings.humanColor().chineseName() + "。", Theme.SUCCESS);
        refreshAll();
        startAiTurn(false);
    }

    private void showGameSettings() {
        GameSettings selected = GameSettingsDialog.showDialog(this, settings);
        if (selected == null || !confirmNewGame()) return;
        invalidateAiRequest();
        settings = selected;
        try {
            settings.save(AppPaths.dataDirectory().resolve("settings.xml"));
        } catch (IOException exception) {
            JOptionPane.showMessageDialog(this, "设置已在本次运行生效，但无法写入磁盘。请检查数据目录权限。",
                    "设置没有保存", JOptionPane.WARNING_MESSAGE);
        }
        beginNewGame();
    }

    private void resign() {
        if (state.gameOver()) return;
        if (JOptionPane.showConfirmDialog(this, "确认认输并保存这盘棋吗？", "结束这盘棋",
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.QUESTION_MESSAGE) != JOptionPane.OK_OPTION) return;
        if (state.gameOver()) return;
        invalidateAiRequest();
        undoStack.addLast(state.copy());
        state.resign(settings.humanColor());
        recorder.event("resign", settings.humanColor().chineseName() + "认输", state);
        finishGame();
    }

    private void invalidateAiRequest() {
        revision++;
        aiThinking = false;
        scoring = false;
        engineVerdict = null;
        if (scoreWorker != null && !scoreWorker.isDone()) scoreWorker.cancel(true);
        lastAiStatus = opponent == localOpponent ? "离线" : "请求已取消";
        if (aiWorker != null && !aiWorker.isDone()) aiWorker.cancel(true);
    }

    private void finishGame() {
        String text;
        if (state.resignedBy() != null) {
            Stone winner = state.resignedBy().opposite();
            resultSummary = winner == settings.humanColor() ? "你赢了 · 对手认输" : "本局落败 · 你已认输";
            text = state.resignedBy().chineseName() + "认输，" + winner.chineseName() + "获胜。棋谱已保留，可随时复盘。";
        } else {
            resultSummary = scoreEngine == null ? "等待结算" : "KataGo 正在判定";
            text = scoreEngine == null ? "双方停手，可以开始数子。" : "双方停手，正在检查死活与地盘。";
        }
        recorder.finish(state, resultSummary + "。" + text);
        AppLogs.event("game", "finished", Map.of("gameId", recorder.id(), "outcome", resultSummary));
        narratives.add(new Narrative(state.moveNumber(), MessageKind.FINISH, resultSummary, text));
        refreshAll();
        rebuildMessages();
        if (state.resignedBy() == null && scoreEngine != null) startEngineScoring();
    }

    private void showScoring() {
        if (!state.gameOver() || state.resignedBy() != null || confirmedScore != null || scoring) return;
        if (scoreEngine != null) { startEngineScoring(); return; }
        reviewScore();
    }

    private void reviewScore() {
        if (!state.gameOver() || state.resignedBy() != null || scoring) return;
        ScoringDialog.Result choice = ScoringDialog.showDialog(this, state.copy(), engineVerdict, confirmedScore);
        if (choice.action() == ScoringDialog.Action.CONTINUE) {
            resumeAfterPasses();
        } else if (choice.action() == ScoringDialog.Action.CONFIRM) {
            confirmFinalScore(choice.score());
        }
    }

    private void confirmFinalScore(FinalScore score) {
        confirmFinalScore(score, "用户确认");
    }

    private void confirmFinalScore(FinalScore score, String source) {
        if (!state.gameOver() || state.resignedBy() != null) return;
        recorder.confirmScore(state, score, source);
        if (!recorder.error().isBlank()) {
            reportStorageFailure();
            return;
        }
        confirmedScore = score;
        String origin = source.startsWith("KataGo") ? "KataGo 判定" : "已确认结算";
        resultSummary = (score.winner() == Stone.EMPTY ? "本局和棋" : score.winner() == settings.humanColor() ? "你赢了" : "本局落败")
                + " · " + origin;
        narratives.removeIf(message -> message.kind() == MessageKind.FINISH);
        narratives.add(new Narrative(state.moveNumber(), MessageKind.FINISH, resultSummary,
                (score.winner() == Stone.EMPTY ? "双方同分" : score.winner().chineseName() + "胜 " + FinalScore.format(score.margin()) + " 点")
                        + "\n黑 " + FinalScore.format(score.blackTotal()) + " · 白 " + FinalScore.format(score.whiteTotal()) + "（含贴目）"));
        AppLogs.event("game", "score_confirmed", Map.of("gameId", recorder.id(), "winner", score.winner().name(),
                "margin", score.margin(), "outcome", resultSummary));
        refreshAll();
        rebuildMessages();
    }

    /** Configure file paths only; the subprocess starts on demand after both players pass. */
    public void useBundledEngine() {
        try {
            scoreEngine = new KataGoEngine(KataGoConfig.discover());
            engineLabel.setText("终局判定 · KataGo");
            engineLabel.setToolTipText("本地运行 · 中国面积规则");
        } catch (IOException exception) {
            engineLabel.setText("KataGo 未安装 · 手动数子");
            engineLabel.setToolTipText(exception.getMessage());
            AppLogs.event("engine", "unavailable", Map.of("reason", exception.getMessage()));
        }
    }

    private void startEngineScoring() {
        if (scoreEngine == null || scoring || !state.gameOver() || state.resignedBy() != null || confirmedScore != null) return;
        scoring = true;
        final long expectedRevision = revision;
        final BoardState position = state.copy();
        final List<Move> history = recorder.moveHistory();
        final ScoreEngine evaluator = scoreEngine;
        resultSummary = "KataGo 正在判定…";
        AppLogs.event("engine", "started", Map.of("gameId", recorder.id(), "moveNumber", position.moveNumber()));
        refreshAll();
        scoreWorker = new SwingWorker<>() {
            @Override protected EngineVerdict doInBackground() throws Exception { return evaluator.evaluate(position, history); }
            @Override protected void done() {
                if (expectedRevision != revision || isCancelled()) return;
                scoring = false;
                try {
                    engineVerdict = get();
                    AppLogs.event("engine", "completed", Map.of("gameId", recorder.id(), "engine", engineVerdict.engine(),
                            "model", engineVerdict.model(), "gtpScore", engineVerdict.rawScore(), "durationMs", engineVerdict.elapsedMs(),
                            "deadStones", engineVerdict.score().deadStones().size(), "consistent", engineVerdict.consistent()));
                    recorder.event("ENGINE_ADJUDICATION", engineVerdict.source() + "；GTP=" + engineVerdict.rawScore()
                            + "；计数一致=" + engineVerdict.consistent() + "；耗时=" + engineVerdict.elapsedMs() + "ms", state);
                    if (engineVerdict.consistent()) {
                        confirmFinalScore(engineVerdict.score(), engineVerdict.source());
                    } else {
                        resultSummary = "尚需收官 · 查看计分";
                        narratives.removeIf(message -> message.kind() == MessageKind.FINISH);
                        narratives.add(new Narrative(state.moveNumber(), MessageKind.FINISH, "KataGo · 尚需收官",
                                "引擎估分与当前面积计数不一致。可以查看计分，或继续把这盘棋下完。"));
                    }
                } catch (CancellationException ignored) {
                } catch (Exception exception) {
                    resultSummary = "引擎暂不可用 · 可重试";
                    Throwable cause = exception.getCause() == null ? exception : exception.getCause();
                    String reason = cause instanceof com.yiyan.go.engine.EngineException
                            ? cause.getMessage() : "KataGo 启动或通信失败";
                    AppLogs.event("engine", "failed", Map.of("gameId", recorder.id(), "reason", reason,
                            "errorCode", "engine_failure", "errorClass", cause.getClass().getSimpleName()));
                    recorder.event("ENGINE_FAILED", reason, state);
                    narratives.removeIf(message -> message.kind() == MessageKind.FINISH);
                    narratives.add(new Narrative(state.moveNumber(), MessageKind.FINISH, "判定未完成", "可重试终局判定，或打开计分手动核对。"));
                }
                refreshAll();
                rebuildMessages();
            }
        };
        scoreWorker.execute();
    }

    private void resumeAfterPasses() {
        if (!state.gameOver() || state.resignedBy() != null) return;
        BoardState resumePosition = undoStack.stream().filter(snapshot -> snapshot.moveNumber() == state.moveNumber() - 2)
                .findFirst().orElse(null);
        if (resumePosition == null) return;
        invalidateAiRequest();
        state = resumePosition.copy();
        confirmedScore = null;
        resultSummary = "";
        undoStack.removeIf(snapshot -> snapshot.moveNumber() >= state.moveNumber());
        recorder.undo(state);
        recorder.event("SCORING_RESUMED", "结算未达成确认，撤回末尾两次停手继续对弈", state);
        narratives.removeIf(message -> message.kind() == MessageKind.FINISH || message.moveNumber() > state.moveNumber());
        narratives.add(new Narrative(state.moveNumber(), MessageKind.SYSTEM, "继续把这盘棋下清楚", "末尾两次停手已撤回，原始事件仍保留。"));
        humanTurnStarted = System.nanoTime();
        refreshAll();
        rebuildMessages();
        startAiTurn(false);
    }

    /** Called only by the real launcher; tests remain offline unless they inject an opponent. */
    public void useEnvironmentConfiguration() {
        String key = System.getenv("DEEPSEEK_API_KEY");
        if (key == null || key.isBlank()) return;
        try {
            String endpoint = System.getenv("DEEPSEEK_ENDPOINT");
            String model = System.getenv("DEEPSEEK_MODEL");
            deepSeekConfig = DeepSeekConfig.of(key,
                    endpoint == null || endpoint.isBlank() ? DeepSeekConfig.DEFAULT_ENDPOINT : endpoint,
                    model == null || model.isBlank() ? DeepSeekConfig.DEFAULT_MODEL : model);
            opponent = new DeepSeekOpponent(deepSeekConfig, recorder.id());
            lastAiStatus = "已读取环境配置 · 待请求";
            recorder.opponentChanged(opponent.displayName(), state);
            resetNarratives();
            narratives.add(new Narrative(state.moveNumber(), MessageKind.SYSTEM, "已读取 DeepSeek 环境配置",
                    "准备好了，轮到 AI 时开始连接。"));
            refreshAll();
            rebuildMessages();
        } catch (RuntimeException exception) {
            deepSeekConfig = null;
            opponent = localOpponent;
            narratives.add(new Narrative(0, MessageKind.WARNING, "环境配置未启用", "请在对手设置中检查 API 地址和模型。"));
            refreshAll();
            rebuildMessages();
        }
    }

    private void showConnectionDialog() {
        if (aiThinking) {
            showHint("这一手结束后再更换对手，避免打断当前计算。", Theme.WARNING);
            return;
        }
        ConnectionDialog.Result result = ConnectionDialog.showDialog(this);
        if (!result.confirmed()) return;
        if (result.config() == null) {
            deepSeekConfig = null;
            opponent = localOpponent;
            lastAiStatus = "离线";
            narratives.add(new Narrative(state.moveNumber(), MessageKind.SYSTEM,
                    "对手已切换", "接下来的 AI 回合由本地陪练落子，不需要联网。"));
        } else {
            deepSeekConfig = result.config();
            opponent = new DeepSeekOpponent(deepSeekConfig, recorder.id());
            lastAiStatus = "待请求";
            narratives.add(new Narrative(state.moveNumber(), MessageKind.SYSTEM,
                    "DeepSeek 已设置 · 尚未验证", "从下一次 AI 回合开始，我会把当前棋盘交给 "
                    + "DeepSeek。"));
        }
        recorder.opponentChanged(AppLogs.redact(opponent.displayName()), state);
        refreshAll();
        rebuildMessages();
        startAiTurn(false);
    }

    private boolean canHumanPlay() {
        return !aiThinking && !state.gameOver() && state.turn() == settings.humanColor();
    }

    private long humanElapsedMs() {
        return Math.max(0, (System.nanoTime() - humanTurnStarted) / 1_000_000);
    }

    private void refreshAll() {
        String turnText;
        String detail;
        Color turnColor = Theme.TEXT;
        if (state.gameOver()) {
            turnText = state.resignedBy() == null && confirmedScore == null ? "等待结算" : "棋局结束";
            detail = resultSummary;
            turnColor = Theme.TEXT_SECONDARY;
        } else if (aiThinking) {
            turnText = "我在想这一手…";
            detail = lastAiStatus;
            turnColor = Theme.WARNING;
        } else if (state.turn() == settings.humanColor()) {
            turnText = "轮到你了";
            detail = "你执" + settings.humanColor().chineseName();
        } else {
            turnText = "AI 回合暂停";
            detail = "可重试或更换对手";
        }
        turnLabel.setText(turnText);
        turnLabel.setForeground(turnColor);
        statusDetailLabel.setText(detail);
        captureLabel.setText("黑提 " + state.blackCaptures() + "  ·  白提 "
                + state.whiteCaptures() + "  ·  " + state.moveNumber() + " 手");

        subtitleLabel.setText(state.size() + " 路 · 基础中国面积规则 · 简单劫 · 你执"
                + settings.humanColor().chineseName() + " · 贴 " + state.komi() + " 目");
        String providerName = AppLogs.redact(opponent.displayName());
        providerButton.setText(providerName.length() > 25 ? providerName.substring(0, 24) + "…" : providerName);
        providerButton.setToolTipText(providerName + " · " + lastAiStatus);
        providerButton.setPreferredSize(new Dimension(230, 38));
        navProviderButton.setText(opponent == localOpponent ? "本地陪练 · 离线" : "DeepSeek · " + lastAiStatus);
        navProviderButton.setToolTipText(providerName + " · " + lastAiStatus);
        providerButton.setEnabled(!aiThinking);
        navProviderButton.setEnabled(!aiThinking);
        undoButton.setEnabled(settings.allowUndo() && !undoStack.isEmpty());
        passButton.setEnabled(canHumanPlay());
        passButton.setVisible(!state.gameOver() && (aiThinking || state.turn() == settings.humanColor()));
        scoringButton.setVisible(state.gameOver() && state.resignedBy() == null && confirmedScore == null);
        scoringButton.setEnabled(!scoring);
        scoringButton.setText(scoring ? "正在判定…" : "终局判定");
        reviewScoreButton.setVisible(state.gameOver() && state.resignedBy() == null);
        reviewScoreButton.setEnabled(!scoring);
        resignButton.setEnabled(!state.gameOver());
        resignButton.setVisible(!state.gameOver());
        retryButton.setVisible(!aiThinking && !state.gameOver() && state.turn() != settings.humanColor());
        recentTitle.setText("你执" + settings.humanColor().chineseName() + " · "
                + (!state.gameOver() ? "进行中" : state.resignedBy() == null && confirmedScore == null ? "待结算" : "已结束"));
        recentSubtitle.setText(state.size() + " 路 · " + state.moveNumber() + " 手 · 查看记录 →");
        FinalScore marks = confirmedScore != null ? confirmedScore : engineVerdict == null ? null : engineVerdict.score();
        boardPanel.setScoreMarks(marks == null ? java.util.Set.of() : marks.deadStones(),
                marks == null ? java.util.Set.of() : marks.neutralPoints());
        boardPanel.repaint();
        if (hintLabel.getText() == null || hintLabel.getText().isBlank()) {
            setDefaultHint();
        }
        reportStorageFailure();
    }

    private void resetNarratives() {
        narratives.clear();
        String name = opponent.displayName();
        narratives.add(new Narrative(0, MessageKind.WELCOME, "开始这盘棋",
                "你执" + settings.humanColor().chineseName() + "。黑棋先行，慢慢想。"));
        rebuildMessages();
    }

    private void rebuildMessages() {
        messageList.removeAll();
        for (Narrative narrative : narratives) {
            messageList.add(messageComponent(narrative));
            messageList.add(Box.createVerticalStrut(narrative.kind() == MessageKind.PLAYER ? 9 : 13));
        }
        if (aiThinking) {
            messageList.add(thinkingComponent());
            messageList.add(Box.createVerticalStrut(10));
        }
        messageList.add(Box.createVerticalGlue());
        messageList.revalidate();
        messageList.repaint();
        SwingUtilities.invokeLater(this::scrollMessagesToBottom);
    }

    private JComponent messageComponent(Narrative narrative) {
        if (narrative.kind() == MessageKind.PLAYER) {
            JPanel event = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
            event.setOpaque(false);
            event.setAlignmentX(Component.LEFT_ALIGNMENT);
            JLabel dot = new JLabel("●");
            dot.setFont(Theme.body(8));
            dot.setForeground(Theme.INK);
            JLabel meta = new JLabel(narrative.meta());
            meta.setFont(Theme.body(10));
            meta.setForeground(Theme.TEXT_MUTED);
            event.add(dot);
            event.add(meta);
            return event;
        }

        Color fill = switch (narrative.kind()) {
            case WELCOME, AI -> Theme.ACCENT_SOFT;
            case WARNING -> new Color(0xF8ECE8);
            case FINISH -> new Color(0xEAF0E8);
            default -> Theme.SURFACE_ALT;
        };
        Color border = switch (narrative.kind()) {
            case WARNING -> new Color(0xEACCC4);
            case FINISH -> new Color(0xCBDCCF);
            default -> null;
        };
        RoundedPanel bubble = new RoundedPanel(fill, border, 14);
        bubble.setLayout(new BoxLayout(bubble, BoxLayout.Y_AXIS));
        bubble.setBorder(BorderFactory.createEmptyBorder(13, 14, 13, 14));
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT);

        JTextArea meta = wrappedText(narrative.meta(), 28, Theme.bodyMedium(10));
        meta.setForeground(narrative.kind() == MessageKind.WARNING ? Theme.DANGER
                : narrative.kind() == MessageKind.FINISH ? Theme.SUCCESS : Theme.ACCENT_DARK);
        meta.setAlignmentX(Component.LEFT_ALIGNMENT);
        bubble.add(meta);
        if (narrative.text() != null && !narrative.text().isBlank()) {
            bubble.add(Box.createVerticalStrut(7));
            JTextArea body = wrappedText(narrative.text(), 28);
            body.setAlignmentX(Component.LEFT_ALIGNMENT);
            bubble.add(body);
        }
        return bubble;
    }

    private JComponent thinkingComponent() {
        RoundedPanel bubble = new RoundedPanel(Theme.SURFACE_ALT, null, 14);
        bubble.setLayout(new BorderLayout(8, 0));
        bubble.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
        bubble.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel pulse = new JLabel("●  ·  ·");
        pulse.setFont(Theme.bodyMedium(12));
        pulse.setForeground(Theme.ACCENT);
        JLabel copy = new JLabel(opponent == localOpponent || lastAiStatus.equals("本地接续中") ? "我在核对几种走法…"
                : "DeepSeek 正在想这一手…");
        copy.setFont(Theme.body(12));
        copy.setForeground(Theme.TEXT_SECONDARY);
        bubble.add(pulse, BorderLayout.WEST);
        bubble.add(copy, BorderLayout.CENTER);
        return bubble;
    }

    private JTextArea wrappedText(String text, int columns) {
        return wrappedText(text, columns, Theme.body(13));
    }

    private JTextArea wrappedText(String text, int columns, java.awt.Font font) {
        JTextArea area = new JTextArea(text);
        area.setColumns(0);
        area.setRows(1);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setEditable(false);
        area.setFocusable(true);
        area.setOpaque(false);
        area.setFont(font);
        area.setForeground(Theme.TEXT);
        area.setBorder(BorderFactory.createEmptyBorder());
        area.getAccessibleContext().setAccessibleName("落子说明正文");
        return area;
    }

    private void scrollMessagesToBottom() {
        JScrollBar bar = messageScroll.getVerticalScrollBar();
        bar.setValue(bar.getMaximum());
    }

    private void showHint(String text, Color color) {
        int token = ++hintRevision;
        hintLabel.setText(text);
        hintLabel.setForeground(color);
        Timer timer = new Timer(3600, event -> {
            if (token == hintRevision) setDefaultHint();
        });
        timer.setRepeats(false);
        timer.start();
    }

    private void setDefaultHint() {
        hintRevision++;
        hintLabel.setText("点击交叉点落子；也可以用方向键与 Enter。 ");
        hintLabel.setForeground(Theme.TEXT_MUTED);
        reportStorageFailure();
    }

    private void reportStorageFailure() {
        if (recorder != null && !recorder.error().isBlank()) {
            hintLabel.setText("对局记录写入失败，请检查数据目录权限。");
            hintLabel.setForeground(Theme.DANGER);
        } else if (!AppLogs.error().isBlank()) {
            hintLabel.setText("日志写入失败，请检查数据目录权限。");
            hintLabel.setForeground(Theme.DANGER);
        }
    }

    private JComponent navigationButton(String text, Runnable action) {
        SoftButton button = new SoftButton(text, SoftButton.Style.GHOST);
        button.setHorizontalAlignment(javax.swing.SwingConstants.LEFT);
        button.setAlignmentX(Component.LEFT_ALIGNMENT);
        button.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        button.addActionListener(event -> action.run());
        return button;
    }

    private JLabel sectionLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(Theme.bodyMedium(10));
        label.setForeground(Theme.TEXT_MUTED);
        label.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 0));
        label.setAlignmentX(Component.LEFT_ALIGNMENT);
        return label;
    }

    private JComponent navItem(String marker, String text, boolean active) {
        JPanel item = new JPanel(new FlowLayout(FlowLayout.LEFT, 10, 0));
        item.setOpaque(active);
        item.setBackground(active ? new Color(255, 255, 255, 125) : Theme.SIDEBAR);
        item.setBorder(BorderFactory.createEmptyBorder(9, 10, 9, 10));
        item.setMaximumSize(new Dimension(Integer.MAX_VALUE, 38));
        item.setAlignmentX(Component.LEFT_ALIGNMENT);
        JLabel icon = new JLabel(marker);
        icon.setFont(Theme.body(12));
        icon.setForeground(active ? Theme.ACCENT : Theme.TEXT_MUTED);
        JLabel label = new JLabel(text);
        label.setFont(active ? Theme.bodyMedium(12) : Theme.body(12));
        label.setForeground(active ? Theme.TEXT : Theme.TEXT_SECONDARY);
        item.add(icon);
        item.add(label);
        return item;
    }

    private JComponent recentGameCard() {
        RoundedPanel card = new RoundedPanel(new Color(255, 255, 255, 78), null, 11);
        card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
        card.setBorder(BorderFactory.createEmptyBorder(10, 11, 10, 11));
        card.setMaximumSize(new Dimension(Integer.MAX_VALUE, 62));
        card.setAlignmentX(Component.LEFT_ALIGNMENT);
        recentTitle.setFont(Theme.bodyMedium(11));
        recentTitle.setForeground(Theme.TEXT_SECONDARY);
        recentSubtitle.setFont(Theme.body(9));
        recentSubtitle.setForeground(Theme.TEXT_MUTED);
        card.add(recentTitle);
        card.add(Box.createVerticalStrut(4));
        card.add(recentSubtitle);
        card.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        card.setToolTipText("查看已保存的对局和复盘");
        card.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override public void mouseClicked(java.awt.event.MouseEvent event) {
                ReplayDialog.showDialog(MainFrame.this);
            }
        });
        return card;
    }

    private static final class LogoMark extends JComponent {
        private LogoMark() {
            setPreferredSize(new Dimension(34, 34));
        }

        @Override
        protected void paintComponent(Graphics graphics) {
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Theme.ACCENT);
            g.fillOval(1, 1, 31, 31);
            g.setColor(Theme.SURFACE);
            g.fillOval(8, 8, 17, 17);
            g.setColor(Theme.INK);
            g.fillOval(13, 13, 7, 7);
            g.dispose();
        }
    }

}
