package com.yiyan.go.engine;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public record KataGoConfig(Path executable, Path model, Path config) {
    public static final String MODEL = "kata1-b18c384nbt-s9996604416-d4316597426.bin.gz";

    public KataGoConfig {
        executable = executable.toAbsolutePath().normalize();
        model = model.toAbsolutePath().normalize();
        config = config.toAbsolutePath().normalize();
    }

    public void validate() throws IOException {
        if (!Files.isRegularFile(executable) || !Files.isRegularFile(model) || !Files.isRegularFile(config)) {
            throw new IOException("未找到完整的 KataGo 引擎和模型，请运行 install-katago.ps1。");
        }
    }

    public static KataGoConfig discover() throws IOException {
        String override = System.getProperty("yiyan.katago.dir");
        if (override != null && !override.isBlank()) return fromDirectory(Path.of(override));
        List<Path> roots = new ArrayList<>();
        try {
            Path code = Path.of(KataGoConfig.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            Path parent = code.getParent();
            if (parent != null) {
                roots.add(parent.resolve("engine/katago"));
                if (parent.getParent() != null) roots.add(parent.getParent().resolve("engine/katago"));
            }
        } catch (Exception ignored) { /* Fall through to development working directory. */ }
        roots.add(Path.of("engine/katago"));
        for (Path root : roots) {
            if (Files.isRegularFile(root.resolve("katago.exe"))) return fromDirectory(root);
        }
        throw new IOException("KataGo 未安装");
    }

    private static KataGoConfig fromDirectory(Path root) throws IOException {
        KataGoConfig result = new KataGoConfig(root.resolve("katago.exe"), root.resolve(MODEL), root.resolve("yiyan-gtp.cfg"));
        result.validate();
        return result;
    }
}
