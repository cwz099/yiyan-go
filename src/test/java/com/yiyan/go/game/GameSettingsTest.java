package com.yiyan.go.game;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class GameSettingsTest {
    @TempDir
    Path directory;

    @Test
    void settingsRoundTripAndOverwriteWithoutCredentials() throws IOException {
        Path file = directory.resolve("nested/game-settings.xml");
        GameSettings settings = new GameSettings(9, 6.5, Stone.WHITE, false, false);
        settings.save(file);
        assertEquals(settings, GameSettings.load(file));
        String text = Files.readString(file);
        assertFalse(text.toLowerCase().contains("apikey"));
        assertFalse(text.toLowerCase().contains("authorization"));
        GameSettings.defaults().save(file);
        assertEquals(GameSettings.defaults(), GameSettings.load(file));
        try (var files = Files.list(file.getParent())) {
            assertEquals(1, files.count(), "临时保存文件应已清理");
        }
    }

    @Test
    void rankedModeAndDifficultyRoundTrip() throws IOException {
        Path file = directory.resolve("ranked.xml");
        GameSettings settings = new GameSettings(19, 7.5, Stone.BLACK,
                true, false, true, GoDifficulty.NINE);
        settings.save(file);
        assertEquals(settings, GameSettings.load(file));
        assertTrue(GameSettings.load(file).rankedMode());
        assertEquals(GoDifficulty.NINE, GameSettings.load(file).difficulty());
    }

    @Test
    void missingAndCorruptSettingsFallBackSafely() throws IOException {
        assertEquals(GameSettings.defaults(), GameSettings.load(null));
        assertEquals(GameSettings.defaults(), GameSettings.load(directory.resolve("missing.xml")));
        Path file = directory.resolve("broken.xml");
        Files.writeString(file, "not xml");
        assertEquals(GameSettings.defaults(), GameSettings.load(file));
        assertEquals(GameSettings.defaults(), GameSettings.load(directory));
    }

    @Test
    void invalidPersistedFieldsFallBackToDefaults() throws IOException {
        Path file = directory.resolve("invalid.xml");
        GameSettings settings = new GameSettings(13, 0, Stone.WHITE, false, false);
        settings.save(file);
        String valid = Files.readString(file);
        for (String invalid : new String[]{
                valid.replace(">13<", ">10<"),
                valid.replace(">0.0<", ">NaN<"),
                valid.replace(">WHITE<", ">EMPTY<"),
                valid.replace(">false<", ">perhaps<")}) {
            Files.writeString(file, invalid);
            assertEquals(GameSettings.defaults(), GameSettings.load(file));
        }
    }

    @Test
    void validatesBoardSizeColorAndHalfPointKomi() {
        assertThrows(IllegalArgumentException.class, () -> new GameSettings(5, 7.5, Stone.BLACK, true, true));
        assertThrows(IllegalArgumentException.class, () -> new GameSettings(19, 7.25, Stone.BLACK, true, true));
        assertThrows(IllegalArgumentException.class, () -> new GameSettings(19, 31, Stone.BLACK, true, true));
        assertThrows(IllegalArgumentException.class, () -> new GameSettings(19, -0.5, Stone.BLACK, true, true));
        assertThrows(IllegalArgumentException.class, () -> new GameSettings(19, Double.NaN, Stone.BLACK, true, true));
        assertThrows(IllegalArgumentException.class, () -> new GameSettings(19, 7.5, Stone.EMPTY, true, true));
        assertThrows(IllegalArgumentException.class, () -> new GameSettings(19, 7.5, null, true, true));
        assertThrows(IllegalArgumentException.class,
                () -> new GameSettings(19, 7.5, Stone.BLACK, true, true, true, null));
        assertDoesNotThrow(() -> new GameSettings(13, 30, Stone.WHITE, false, false));
    }

    @Test
    void saveFailureIsReportedWithoutLeavingTemporaryFile() throws IOException {
        Path target = directory.resolve("nonempty-directory");
        Files.createDirectory(target);
        Files.writeString(target.resolve("keep.txt"), "retain");
        assertThrows(IOException.class, () -> GameSettings.defaults().save(target));
        assertEquals("retain", Files.readString(target.resolve("keep.txt")));
        try (var files = Files.list(directory)) {
            assertEquals(1, files.count());
        }
    }
}
