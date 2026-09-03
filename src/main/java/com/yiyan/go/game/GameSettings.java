package com.yiyan.go.game;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Properties;

/** Preferences for new games. API credentials are deliberately stored elsewhere. */
public record GameSettings(int boardSize, double komi, Stone humanColor,
                           boolean allowUndo, boolean autoFallback) {
    public GameSettings {
        if (boardSize != 9 && boardSize != 13 && boardSize != 19) {
            throw new IllegalArgumentException("请选择 9、13 或 19 路棋盘");
        }
        if (!Double.isFinite(komi) || komi < 0 || komi > 30 || komi * 2 != Math.rint(komi * 2)) {
            throw new IllegalArgumentException("贴目须为 0 到 30 之间的整数或半目");
        }
        if (humanColor != Stone.BLACK && humanColor != Stone.WHITE) {
            throw new IllegalArgumentException("请选择执黑或执白");
        }
    }

    public static GameSettings defaults() {
        return new GameSettings(19, 7.5, Stone.BLACK, true, true);
    }

    /** Missing, corrupt, or incompatible settings never prevent the app from starting. */
    public static GameSettings load(Path file) {
        if (file == null) return defaults();
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(file)) {
            properties.loadFromXML(input);
            return new GameSettings(
                    Integer.parseInt(properties.getProperty("boardSize", "19")),
                    Double.parseDouble(properties.getProperty("komi", "7.5")),
                    Stone.valueOf(properties.getProperty("humanColor", "BLACK")),
                    parseBoolean(properties.getProperty("allowUndo", "true")),
                    parseBoolean(properties.getProperty("autoFallback", "true")));
        } catch (IOException | IllegalArgumentException | SecurityException exception) {
            return defaults();
        }
    }

    public void save(Path file) throws IOException {
        Path target = file.toAbsolutePath().normalize();
        Path parent = target.getParent();
        Files.createDirectories(parent);
        Path temporary = Files.createTempFile(parent, ".game-settings-", ".tmp");
        try {
            Properties properties = new Properties();
            properties.setProperty("boardSize", Integer.toString(boardSize));
            properties.setProperty("komi", Double.toString(komi));
            properties.setProperty("humanColor", humanColor.name());
            properties.setProperty("allowUndo", Boolean.toString(allowUndo));
            properties.setProperty("autoFallback", Boolean.toString(autoFallback));
            try (OutputStream output = Files.newOutputStream(temporary)) {
                properties.storeToXML(output, "弈言 · 对局设置", "UTF-8");
            }
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static boolean parseBoolean(String value) {
        if ("true".equalsIgnoreCase(value)) return true;
        if ("false".equalsIgnoreCase(value)) return false;
        throw new IllegalArgumentException("无效的布尔设置");
    }
}
