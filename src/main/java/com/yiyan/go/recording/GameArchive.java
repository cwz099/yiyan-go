package com.yiyan.go.recording;

import com.yiyan.go.diagnostics.AppPaths;
import com.yiyan.go.game.Move;
import com.yiyan.go.game.Stone;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/** Reads validated game records. A damaged file is preserved in quarantine, never silently discarded. */
public final class GameArchive {
    private static final int MAX_LISTED = 200;
    private static final int MAX_SCANNED = 2_000;

    public record ScanResult(List<GameRecord> games, List<String> warnings) {
        public ScanResult { games = List.copyOf(games); warnings = List.copyOf(warnings); }
    }

    private GameArchive() { }

    public static Path directory() { return AppPaths.dataDirectory().resolve("games"); }

    public static ScanResult list() { return list(directory()); }

    static ScanResult list(Path directory) {
        List<GameRecord> games = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (!Files.exists(directory)) return new ScanResult(games, warnings);
        try {
            List<Path> paths = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            int scanned = 0;
            try (var entries = Files.newDirectoryStream(directory)) {
                for (Path path : entries) {
                    if (++scanned > MAX_SCANNED) { warnings.add("记录文件较多，仅扫描前 2000 个条目；旧文件未删除。"); break; }
                    String file = path.getFileName().toString();
                    if (!file.matches("[0-9a-f-]{36}\\.xml(?:\\.bak)?")
                            || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
                    String id = file.substring(0, 36);
                    if (seen.add(id)) paths.add(directory.resolve(id + ".xml"));
                }
            }
            paths.sort(Comparator.comparingLong(GameArchive::modified).reversed());
            if (paths.size() > MAX_LISTED) warnings.add("显示最近 200 局；其余记录仍保留在数据目录。");
            for (Path path : paths.subList(0, Math.min(MAX_LISTED, paths.size()))) {
                try {
                    games.add(loadRecovering(path, warnings));
                } catch (IOException | SecurityException e) {
                    warnings.add(path.getFileName() + " 无法读取或恢复；已尽可能隔离原文件。");
                }
            }
            games.sort(Comparator.comparing(GameRecord::updated).reversed());
        } catch (IOException | SecurityException e) {
            warnings.add("无法访问对局数据目录，请检查目录权限。");
        }
        return new ScanResult(games, warnings);
    }

    private static long modified(Path path) {
        try {
            if (!Files.exists(path)) path = path.resolveSibling(path.getFileName() + ".bak");
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException | SecurityException e) { return 0; }
    }

    public static GameRecord load(Path path) throws IOException {
        String filename = path.getFileName().toString();
        if (!filename.matches("[0-9a-f-]{36}\\.xml") || Files.isSymbolicLink(path)) throw new IOException("记录文件名不合法");
        return RecordFormat.decode(RecordFormat.read(path), filename.substring(0, 36));
    }

    private static GameRecord loadRecovering(Path path, List<String> warnings) throws IOException {
        try { return load(path); }
        catch (IOException e) {
            Path backup = path.resolveSibling(path.getFileName() + ".bak");
            GameRecord restored = null;
            byte[] backupBytes = null;
            if (Files.exists(backup, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(backup)) {
                try {
                    Properties properties = RecordFormat.read(backup);
                    restored = RecordFormat.decode(properties, path.getFileName().toString().substring(0, 36));
                    backupBytes = RecordFormat.xml(properties);
                } catch (IOException | RuntimeException backupError) {
                    quarantine(backup);
                }
            }
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) quarantine(path);
            if (restored == null) throw new IOException("主记录和备份均不可用", e);
            RecordFormat.atomicWrite(path, backupBytes);
            warnings.add(path.getFileName() + " 损坏或缺失，已恢复上一份备份；最后一次操作可能缺失，原文件保留于 quarantine。");
            return restored;
        }
    }

    private static void quarantine(Path path) throws IOException {
        Path folder = path.getParent().resolve("quarantine");
        Files.createDirectories(folder);
        Files.move(path, folder.resolve(path.getFileName() + "." + UUID.randomUUID() + ".damaged"), StandardCopyOption.ATOMIC_MOVE);
    }

    public static String toSgf(GameRecord game) {
        String black = game.humanColor() == Stone.BLACK ? "你" : game.opponent();
        String white = game.humanColor() == Stone.WHITE ? "你" : game.opponent();
        StringBuilder sgf = new StringBuilder("(;GM[1]FF[4]CA[UTF-8]AP[Yiyan:1.0]")
                .append("SZ[").append(game.size()).append("]KM[").append(game.komi()).append(']')
                .append("RU[Chinese (simple ko)]PB[").append(escape(black)).append("]PW[").append(escape(white)).append(']')
                .append("DT[").append(DateTimeFormatter.ISO_LOCAL_DATE.format(game.started().atZone(ZoneId.systemDefault()))).append(']');
        if (game.status() == GameRecord.Status.RESIGNED) {
            sgf.append("RE[").append(game.finalPosition().resignedBy() == Stone.BLACK ? "W+R" : "B+R").append(']');
        } else if (game.status() == GameRecord.Status.SCORED) {
            var score = game.finalScore();
            sgf.append("RE[").append(score.winner() == Stone.EMPTY ? "0"
                    : (score.winner() == Stone.BLACK ? "B+" : "W+") + score.margin()).append(']');
        } else if (game.status() == GameRecord.Status.ESTIMATED) {
            // A simple area estimate without dead-stone confirmation is not an official game result.
            sgf.append("RE[?]");
        }
        sgf.append("C[").append(escape("弈言 · " + game.status().label() + "\n" + game.result()
                + "\n采用简单劫规则；仅“已确认结算”的数值结果经过人工死子确认。导出的是悔棋后的活跃主线。" )).append(']');
        for (GameRecord.Ply ply : game.moves()) {
            Move move = ply.move();
            sgf.append(';').append(move.stone() == Stone.BLACK ? 'B' : 'W').append('[');
            if (!move.pass()) sgf.append((char) ('a' + move.point().x())).append((char) ('a' + move.point().y()));
            sgf.append("]C[").append(escape("第 " + move.number() + " 手 · " + move.coordinate(game.size())
                    + "\n来源：" + ply.source() + "\n提子：" + ply.captured().size()
                    + "\n耗时：" + ply.elapsedMs() + " ms\n时间：" + ply.time()
                    + "\n" + ply.motivation())).append(']');
        }
        if (game.finalScore() != null) {
            sgf.append(";C[").append(escape(game.finalScore().summary() + "\n叉号表示人工确认的死子；原始落子不删除。"
                    + "\n中立点：" + RecordFormat.captures(new ArrayList<>(game.finalScore().neutralPoints())))).append(']');
            if (!game.finalScore().deadStones().isEmpty()) {
                sgf.append("MA");
                game.finalScore().deadStones().stream().sorted(Comparator.comparingInt(com.yiyan.go.game.Point::y)
                        .thenComparingInt(com.yiyan.go.game.Point::x)).forEach(point -> sgf.append('[')
                        .append((char) ('a' + point.x())).append((char) ('a' + point.y())).append(']'));
            }
        }
        return sgf.append(")\n").toString();
    }

    public static void exportSgf(GameRecord game, Path target) throws IOException {
        RecordFormat.atomicWrite(target, toSgf(game).getBytes(StandardCharsets.UTF_8));
    }

    private static String escape(String text) {
        return RecordFormat.text(text).replace("\\", "\\\\").replace("]", "\\]").replace("\r\n", "\n").replace('\r', '\n');
    }
}
