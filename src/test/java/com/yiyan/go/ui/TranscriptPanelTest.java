package com.yiyan.go.ui;

import org.junit.jupiter.api.Test;
import javax.swing.*;
import java.awt.*;
import static org.junit.jupiter.api.Assertions.*;

class TranscriptPanelTest {
    @Test void oldRecordDisclaimersAreRemovedOnlyFromDisplay() {
        String original = "DeepSeek 选择停一手。浅层检查未找到既安全又有明确作用的落点；停一手，进入双方确认流程。这不代表已判定死活或胜负。";
        assertEquals("暂不落子，等待对方应手。", ExplanationCopy.forReplay(original, true));
        assertTrue(original.contains("这不代表"));
        assertEquals("连接两块棋。", ExplanationCopy.forReplay("连接两块棋。 以上核验不代表已判断死活或胜负。", false));
    }
    @Test void cardsWrapWithoutClippingAtMultipleWidths() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            TranscriptPanel transcript = new TranscriptPanel();
            RoundedPanel card = new RoundedPanel(Theme.ACCENT_SOFT, null, 14);
            card.setLayout(new BoxLayout(card, BoxLayout.Y_AXIS));
            card.setBorder(BorderFactory.createEmptyBorder(13, 14, 13, 14));
            JTextArea meta = area("白棋 · 第 122 手 · 停一手 · very-long-model-name-without-spaces-0123456789");
            JTextArea body = area("这一手只留下有用的信息。中文正文应根据面板宽度自动换行，长英文也不能溢出或被横向截断。");
            card.add(meta); card.add(Box.createVerticalStrut(7)); card.add(body);
            transcript.add(card);
            JViewport viewport = new JViewport();
            viewport.setView(transcript);
            int narrowHeight = 0;
            for (int width : new int[]{240, 300, 380}) {
                viewport.setSize(width, 600);
                viewport.setExtentSize(new Dimension(width, 600));
                transcript.setSize(width, 600);
                Dimension preferred = transcript.getPreferredSize();
                transcript.doLayout(); card.doLayout();
                assertTrue(card.getWidth() <= width);
                assertTrue(body.getX() + body.getWidth() <= card.getWidth() - 14);
                assertTrue(body.getY() + body.getHeight() <= card.getHeight() - 13);
                assertTrue(meta.getHeight() >= meta.getUI().getPreferredSize(meta).height);
                assertTrue(body.getHeight() >= body.getUI().getPreferredSize(body).height);
                if (width == 240) narrowHeight = preferred.height;
                else assertTrue(preferred.height <= narrowHeight);
            }
        });
    }

    private static JTextArea area(String text) {
        JTextArea area = new JTextArea(text);
        area.setLineWrap(true); area.setWrapStyleWord(true); area.setRows(1);
        area.setFont(Theme.body(13)); area.setAlignmentX(Component.LEFT_ALIGNMENT);
        return area;
    }
}
