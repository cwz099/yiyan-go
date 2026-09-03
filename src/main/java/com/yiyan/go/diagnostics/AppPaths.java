package com.yiyan.go.diagnostics;

import java.nio.file.Path;

/** Writable user data stays outside the packaged application directory. */
public final class AppPaths {
    private AppPaths() { }

    public static Path dataDirectory() {
        String override = System.getProperty("yiyan.dataDir");
        if (override != null && !override.isBlank()) return Path.of(override).toAbsolutePath().normalize();
        String local = System.getenv("LOCALAPPDATA");
        if (System.getProperty("os.name", "").startsWith("Windows") && local != null && !local.isBlank()) {
            return Path.of(local, "Yiyan");
        }
        return Path.of(System.getProperty("user.home"), ".yiyan");
    }
}
