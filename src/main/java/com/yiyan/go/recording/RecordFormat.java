package com.yiyan.go.recording;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.FinalScore;
import com.yiyan.go.game.ScoreAdjudication;
import com.yiyan.go.game.MoveResult;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;
import com.yiyan.go.diagnostics.AppLogs;
import com.yiyan.go.diagnostics.JsonCodec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;

final class RecordFormat {
    static final int VERSION = 2;
    static final int MAX_MOVES = 5_000;
    static final int MAX_EVENTS = 10_000;
    static final int MAX_TEXT = 8_192;
    static final int MAX_BYTES = 32 * 1024 * 1024;
    private RecordFormat() { }

    static String board(BoardState state) {
        StringBuilder text = new StringBuilder(state.size() * state.size());
        for (int y = 0; y < state.size(); y++) {
            for (int x = 0; x < state.size(); x++) {
                text.append(switch (state.stoneAt(x, y)) { case BLACK -> 'B'; case WHITE -> 'W'; case EMPTY -> '.'; });
            }
        }
        return text.toString();
    }

    static String state(BoardState state) {
        return board(state) + "|" + state.turn() + "|" + state.moveNumber() + "|"
                + state.blackCaptures() + "|" + state.whiteCaptures() + "|"
                + state.consecutivePasses() + "|" + state.gameOver() + "|" + resignation(state);
    }

    static Stone resignation(BoardState state) {
        return state.resignedBy() == null ? Stone.EMPTY : state.resignedBy();
    }

    static String captures(List<Point> points) {
        return points.stream().sorted(Comparator.comparingInt(Point::y).thenComparingInt(Point::x))
                .map(p -> p.x() + "," + p.y()).reduce((a, b) -> a + ";" + b).orElse("");
    }

    static String text(String value) {
        if (value == null) return "";
        // Preserve legal XML 1.0 codepoints and never split a UTF-16 surrogate pair at the limit.
        String redacted = AppLogs.redact(value);
        StringBuilder cleaned = new StringBuilder(Math.min(redacted.length(), MAX_TEXT));
        for (int i = 0; i < redacted.length();) {
            int codepoint = redacted.codePointAt(i);
            i += Character.charCount(codepoint);
            if (!(codepoint == 9 || codepoint == 10 || codepoint == 13
                    || codepoint >= 0x20 && codepoint <= 0xD7FF
                    || codepoint >= 0xE000 && codepoint <= 0xFFFD
                    || codepoint >= 0x10000 && codepoint <= 0x10FFFF)) continue;
            if (cleaned.length() + Character.charCount(codepoint) > MAX_TEXT) break;
            cleaned.appendCodePoint(codepoint);
        }
        return cleaned.toString();
    }

    static String json(String value) {
        StringBuilder result = new StringBuilder("\"");
        // Free-form text is sanitized by the recorder before reaching this serializer.
        // Do not redact canonical board/id fields: short, malformed API keys could match them.
        for (char c : (value == null ? "" : value).toCharArray()) {
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> result.append(c);
            }
        }
        return result.append('"').toString();
    }

    static byte[] xml(Properties properties) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        properties.storeToXML(output, "Yiyan game record; UTF-8", "UTF-8");
        if (output.size() > MAX_BYTES) throw new IOException("对局记录超过大小上限");
        return output.toByteArray();
    }

    static Properties read(Path path) throws IOException {
        if (!Files.isRegularFile(path) || Files.size(path) > MAX_BYTES) throw new IOException("记录缺失或过大");
        byte[] bytes;
        try (var input = Files.newInputStream(path)) {
            bytes = input.readNBytes(MAX_BYTES + 1);
        }
        if (bytes.length > MAX_BYTES) throw new IOException("记录超过大小上限");
        Properties properties = new Properties();
        try (var input = new ByteArrayInputStream(bytes)) {
            // JDK's Properties XML reader only accepts its fixed properties DTD.
            properties.loadFromXML(input);
        }
        return properties;
    }

    static void atomicWrite(Path target, byte[] bytes) throws IOException {
        Files.createDirectories(target.toAbsolutePath().getParent());
        Path temporary = Files.createTempFile(target.toAbsolutePath().getParent(), ".record-", ".tmp");
        try {
            Files.write(temporary, bytes);
            Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    static String required(Properties p, String key, int max) throws IOException {
        String value = p.getProperty(key);
        if (value == null || value.length() > max) throw new IOException("记录字段缺失或过长: " + key);
        return value;
    }

    static int integer(Properties p, String key, int min, int max) throws IOException {
        try {
            int value = Integer.parseInt(required(p, key, 12));
            if (value < min || value > max) throw new NumberFormatException();
            return value;
        } catch (NumberFormatException e) { throw new IOException("记录数值不合法: " + key, e); }
    }

    static GameRecord decode(Properties p, String expectedId) throws IOException {
        try {
            int schemaVersion = integer(p, "schemaVersion", 1, VERSION);
            String id = required(p, "id", 36);
            if (!UUID.fromString(id).toString().equals(id) || !id.equals(expectedId)) throw new IOException("记录编号不匹配");
            int size = integer(p, "size", 5, 19);
            double komi = Double.parseDouble(required(p, "komi", 20));
            if (!Double.isFinite(komi) || komi < 0 || komi > 30) throw new IOException("贴目不合法");
            BoardState board = new BoardState(size, komi);
            BoardState initial = board.copy();
            if (!state(board).equals(required(p, "initial", 600))) throw new IOException("初始盘面不匹配");
            Stone human = Stone.valueOf(required(p, "humanColor", 5));
            if (human == Stone.EMPTY) throw new IOException("执棋颜色不合法");
            Instant start = Instant.parse(required(p, "started", 40));
            Instant updated = Instant.parse(required(p, "updated", 40));
            String opponent = required(p, "opponent", MAX_TEXT);
            int count = integer(p, "moves", 0, MAX_MOVES);
            List<GameRecord.Ply> moves = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                String prefix = "move." + i + ".";
                Stone stone = Stone.valueOf(required(p, prefix + "stone", 5));
                if (stone != board.turn()) throw new IOException("行棋颜色不匹配: " + (i + 1));
                String action = required(p, prefix + "action", 10);
                MoveResult played;
                if (action.equals("PASS")) {
                    played = board.pass();
                } else if (action.equals("PLAY")) {
                    played = board.play(integer(p, prefix + "x", 0, size - 1), integer(p, prefix + "y", 0, size - 1));
                } else throw new IOException("未知行棋类型");
                if (!played.success()) throw new IOException("存在非法着手: " + (i + 1));
                if (!captures(played.captured()).equals(required(p, prefix + "captures", 3_000))) {
                    throw new IOException("提子数据不匹配: " + (i + 1));
                }
                if (!state(board).equals(required(p, prefix + "state", 600))) {
                    throw new IOException("盘面快照不匹配: " + (i + 1));
                }
                long elapsed = Long.parseLong(required(p, prefix + "elapsedMs", 20));
                if (elapsed < 0 || elapsed > 7L * 24 * 60 * 60 * 1000) throw new IOException("行棋耗时不合法");
                moves.add(new GameRecord.Ply(played.move(), played.captured(), required(p, prefix + "source", MAX_TEXT),
                        required(p, prefix + "motivation", MAX_TEXT), elapsed,
                        Instant.parse(required(p, prefix + "time", 40)), board));
            }
            GameRecord.Status status = GameRecord.Status.valueOf(required(p, "status", 20));
            Stone resignation = Stone.valueOf(required(p, "resignedBy", 5));
            if (resignation != Stone.EMPTY) {
                if (status != GameRecord.Status.RESIGNED || board.gameOver()) throw new IOException("认输状态冲突");
                board.resign(resignation);
            } else if (status == GameRecord.Status.RESIGNED) throw new IOException("缺少认输方");
            if ((status == GameRecord.Status.ESTIMATED || status == GameRecord.Status.SCORED)
                    && (!board.gameOver() || resignation != Stone.EMPTY)) throw new IOException("结算缺少双方停一手");
            if ((status == GameRecord.Status.IN_PROGRESS || status == GameRecord.Status.PAUSED) && board.gameOver()) {
                throw new IOException("未完局状态与终局盘面冲突");
            }
            if (!state(board).equals(required(p, "final", 600))) throw new IOException("终局快照不匹配");
            FinalScore finalScore = null;
            if (status == GameRecord.Status.SCORED) {
                if (schemaVersion < 2) throw new IOException("旧版记录不支持确认结算");
                finalScore = ScoreAdjudication.calculate(board,
                        pointSet(required(p, "score.dead", 3_000), size),
                        pointSet(required(p, "score.neutral", 3_000), size));
                if (Double.compare(finalScore.blackTotal(), Double.parseDouble(required(p, "score.blackTotal", 30))) != 0
                        || Double.compare(finalScore.whiteTotal(), Double.parseDouble(required(p, "score.whiteTotal", 30))) != 0) {
                    throw new IOException("结算分数与盘面标记不一致");
                }
            } else if (p.stringPropertyNames().stream().anyMatch(key -> key.startsWith("score."))) {
                throw new IOException("非结算状态不能带有已确认分数");
            }
            int events = integer(p, "events", 1, MAX_EVENTS);
            for (int i = 0; i < events; i++) {
                String event = required(p, "event." + i, MAX_TEXT * 10);
                if (!event.startsWith("{") || !event.endsWith("}") || event.indexOf('\n') >= 0 || event.indexOf('\r') >= 0) {
                    throw new IOException("事件日志格式不合法");
                }
                if (!(JsonCodec.parse(event) instanceof Map<?, ?> data)
                        || !Long.valueOf(schemaVersion).equals(data.get("schemaVersion"))
                        || !Long.valueOf(i + 1L).equals(data.get("sequence"))
                        || !id.equals(data.get("gameId"))
                        || !Long.valueOf(size).equals(data.get("boardSize"))
                        || !(data.get("komi") instanceof Number eventKomi)
                        || Double.compare(eventKomi.doubleValue(), komi) != 0
                        || !(data.get("moveNumber") instanceof Long eventMove) || eventMove < 0 || eventMove > MAX_MOVES
                        || !(data.get("type") instanceof String eventType) || eventType.isEmpty()
                        || !(data.get("time") instanceof String eventTime)
                        || !(data.get("board") instanceof String eventBoard)
                        || eventBoard.length() != size * size || !eventBoard.matches("[BW.]+")
                        || !(data.get("state") instanceof String eventState) || !eventState.startsWith(eventBoard + "|")) {
                    throw new IOException("事件日志字段不合法");
                }
                Instant.parse(eventTime);
            }
            return new GameRecord(id, start, updated, human, opponent, initial, board, moves,
                    status, required(p, "result", MAX_TEXT), finalScore);
        } catch (IllegalArgumentException | java.time.DateTimeException e) {
            throw new IOException("对局记录格式不合法", e);
        }
    }

    private static Set<Point> pointSet(String encoded, int size) throws IOException {
        Set<Point> points = new HashSet<>();
        if (encoded.isEmpty()) return points;
        for (String token : encoded.split(";", -1)) {
            String[] xy = token.split(",", -1);
            if (xy.length != 2) throw new IOException("结算坐标格式无效");
            int x = Integer.parseInt(xy[0]);
            int y = Integer.parseInt(xy[1]);
            if (x < 0 || y < 0 || x >= size || y >= size || !points.add(new Point(x, y))) {
                throw new IOException("结算坐标越界或重复");
            }
        }
        return points;
    }
}
