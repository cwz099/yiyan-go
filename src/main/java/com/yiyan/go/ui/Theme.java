package com.yiyan.go.ui;

import javax.swing.BorderFactory;
import javax.swing.UIManager;
import java.awt.Color;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

public final class Theme {
    public static final Color CANVAS = new Color(0xF7F5F0);
    public static final Color SIDEBAR = new Color(0xF0EDE6);
    public static final Color SURFACE = new Color(0xFFFDF8);
    public static final Color SURFACE_ALT = new Color(0xF5F1E9);
    public static final Color TEXT = new Color(0x2C2925);
    public static final Color TEXT_SECONDARY = new Color(0x6D675F);
    public static final Color TEXT_MUTED = new Color(0x756E65);
    public static final Color BORDER = new Color(0xDED8CF);
    public static final Color BORDER_SOFT = new Color(0xE9E4DB);
    public static final Color ACCENT = new Color(0xC46645);
    public static final Color ACCENT_DARK = new Color(0xA94E32);
    public static final Color ACCENT_SOFT = new Color(0xF3E2D9);
    public static final Color INK = new Color(0x302C28);
    public static final Color SUCCESS = new Color(0x4D755E);
    public static final Color WARNING = new Color(0x99672E);
    public static final Color DANGER = new Color(0xA45148);
    public static final Color BOARD = new Color(0xD8B47B);
    public static final Color BOARD_LIGHT = new Color(0xE3C38D);
    public static final Color GRID = new Color(0x55422E);

    private static final String SERIF = chooseFont(
            "Noto Serif CJK SC", "Source Han Serif SC", "思源宋体", "STSong", "SimSun", "Serif");
    private static final String SANS = chooseFont(
            "Noto Sans CJK SC", "Source Han Sans SC", "思源黑体", "Microsoft YaHei UI", "Microsoft YaHei", "SansSerif");
    private static final String MONO = chooseFont(
            "IBM Plex Mono", "Cascadia Mono", "Consolas", "Monospaced");

    private Theme() {
    }

    public static void installDefaults() {
        UIManager.put("Panel.background", CANVAS);
        UIManager.put("Label.foreground", TEXT);
        UIManager.put("Label.font", body(14));
        UIManager.put("Button.font", bodyMedium(14));
        UIManager.put("Button.focus", new Color(0, 0, 0, 0));
        UIManager.put("TextField.font", body(14));
        UIManager.put("PasswordField.font", body(14));
        UIManager.put("TextArea.font", body(14));
        UIManager.put("ScrollPane.border", BorderFactory.createEmptyBorder());
        UIManager.put("ScrollBar.width", 9);
        UIManager.put("ToolTip.font", body(12));
        UIManager.put("ToolTip.background", INK);
        UIManager.put("ToolTip.foreground", Color.WHITE);
        UIManager.put("ToolTip.border", BorderFactory.createEmptyBorder(7, 9, 7, 9));
    }

    public static Font display(int size) {
        return new Font(SERIF, Font.BOLD, size);
    }

    public static Font body(int size) {
        return new Font(SANS, Font.PLAIN, size);
    }

    public static Font bodyMedium(int size) {
        return new Font(SANS, Font.BOLD, size);
    }

    public static Font mono(int size) {
        return new Font(MONO, Font.PLAIN, size);
    }

    public static Color blend(Color first, Color second, float ratio) {
        float inverse = 1f - ratio;
        return new Color(
                Math.round(first.getRed() * inverse + second.getRed() * ratio),
                Math.round(first.getGreen() * inverse + second.getGreen() * ratio),
                Math.round(first.getBlue() * inverse + second.getBlue() * ratio),
                Math.round(first.getAlpha() * inverse + second.getAlpha() * ratio));
    }

    private static String chooseFont(String... candidates) {
        Set<String> available = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        for (String candidate : candidates) {
            if (available.contains(candidate)) return candidate;
        }
        return candidates[candidates.length - 1];
    }
}
