package com.yiyan.go;

import com.yiyan.go.ui.MainFrame;
import com.yiyan.go.ui.Theme;
import com.yiyan.go.diagnostics.AppLogs;
import java.util.Map;

import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public final class App {
    private App() {
    }

    public static void main(String[] args) {
        AppLogs.event("app", "started", Map.of("status", "starting"));
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) ->
                AppLogs.event("app", "uncaught_exception", Map.of("errorClass", failure.getClass().getSimpleName())));
        System.setProperty("sun.java2d.uiScale.enabled", "true");
        SwingUtilities.invokeLater(() -> {
            Theme.installDefaults();
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                Theme.installDefaults();
            } catch (Exception ignored) {
                // The custom components do not depend on a particular native look and feel.
            }
            MainFrame frame = new MainFrame();
            frame.useBundledEngine();
            frame.useEnvironmentConfiguration();
            frame.setVisible(true);
        });
    }
}
