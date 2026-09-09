package com.yiyan.go.analysis;

import com.yiyan.go.diagnostics.JsonCodec;
import com.yiyan.go.engine.EngineException;
import com.yiyan.go.engine.KataGoConfig;
import com.yiyan.go.game.Move;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;
import com.yiyan.go.recording.GameRecord;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Bounded client for KataGo's asynchronous JSON whole-game analysis protocol. */
final class KataGoAnalysisEngine {
    private static final int MAX_LINE = 2 * 1024 * 1024;
    private static final long MAX_OUTPUT = 32L * 1024 * 1024;

    @FunctionalInterface interface ProcessFactory { ProcessBuilder create() throws IOException; }

    record MoveValue(String move, double blackLead, double blackWinrate, int order, List<String> variation) {
        MoveValue { variation = List.copyOf(variation); }
    }

    record PositionValue(double blackLead, double blackWinrate,
                         Map<String, MoveValue> moves, MoveValue best) {
        PositionValue { moves = Map.copyOf(moves); }
    }

    private final ProcessFactory factory;
    private final Duration timeout;

    KataGoAnalysisEngine(KataGoConfig config) {
        Objects.requireNonNull(config);
        this.factory = () -> process(config);
        this.timeout = Duration.ofMinutes(5);
    }

    KataGoAnalysisEngine(ProcessFactory factory, Duration timeout) {
        this.factory = Objects.requireNonNull(factory);
        this.timeout = Objects.requireNonNull(timeout);
    }

    Map<Integer, PositionValue> analyze(GameRecord game, int visits) throws Exception {
        String id = UUID.randomUUID().toString();
        Process process = null;
        try {
            ProcessBuilder builder = factory.create();
            builder.environment().remove("DEEPSEEK_API_KEY");
            try {
                process = builder.start();
            } catch (IOException exception) {
                throw new EngineException("KataGo 整盘分析无法启动，请检查引擎和模型文件", exception);
            }

            BlockingQueue<String> output = new ArrayBlockingQueue<>(1024);
            AtomicBoolean ended = new AtomicBoolean();
            AtomicReference<String> readFailure = new AtomicReference<>();
            readStdout(process, output, ended, readFailure);
            drain(process.getErrorStream());
            try (BufferedWriter input = process.outputWriter(StandardCharsets.UTF_8)) {
                input.write(query(game, visits, id));
                input.newLine();
            }

            int expected = game.moves().size() + 1;
            Map<Integer, PositionValue> positions = new HashMap<>();
            long deadline = System.nanoTime() + timeout.toNanos();
            while (positions.size() < expected) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                if (System.nanoTime() >= deadline) throw new EngineException("KataGo 整盘分析超时，请稍后重试");
                String line = output.poll(100, TimeUnit.MILLISECONDS);
                if (line != null && !line.isBlank()) accept(line, id, game.size(), expected, positions);
                if (readFailure.get() != null) throw new EngineException(readFailure.get());
                if (ended.get() && output.isEmpty() && positions.size() < expected) {
                    throw new EngineException("KataGo 整盘分析提前结束");
                }
            }
            return Map.copyOf(positions);
        } finally {
            if (process != null) {
                process.destroy();
                if (process.isAlive()) process.destroyForcibly();
            }
        }
    }

    private static ProcessBuilder process(KataGoConfig config) throws IOException {
        config.validate();
        Path analysisConfig = config.config().resolveSibling("analysis_example.cfg");
        if (!Files.isRegularFile(analysisConfig)) {
            throw new EngineException("缺少 KataGo 整盘分析配置，请重新安装引擎");
        }
        ProcessBuilder builder = new ProcessBuilder(config.executable().toString(), "analysis",
                "-model", config.model().toString(), "-config", analysisConfig.toString(),
                "-override-config", "numAnalysisThreads=4,numSearchThreadsPerAnalysisThread=1,"
                + "numEigenThreadsPerModel=4,nnCacheSizePowerOfTwo=18,nnMutexPoolSizePowerOfTwo=14,"
                + "reportAnalysisWinratesAs=BLACK,logToStderr=false");
        return builder.directory(config.executable().getParent().toFile());
    }

    private static String query(GameRecord game, int visits, String id) {
        List<List<String>> moves = new ArrayList<>(game.moves().size());
        for (GameRecord.Ply ply : game.moves()) {
            Move move = ply.move();
            moves.add(List.of(move.stone() == Stone.BLACK ? "B" : "W",
                    move.pass() ? "pass" : move.coordinate(game.size())));
        }
        List<Integer> turns = new ArrayList<>(moves.size() + 1);
        for (int turn = 0; turn <= moves.size(); turn++) turns.add(turn);
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("id", id);
        request.put("moves", moves);
        request.put("rules", "chinese");
        request.put("komi", game.komi());
        request.put("boardXSize", game.size());
        request.put("boardYSize", game.size());
        request.put("analyzeTurns", turns);
        request.put("maxVisits", visits);
        request.put("analysisPVLen", 6);
        return JsonCodec.stringify(request);
    }

    private static void accept(String line, String id, int size, int expected,
                               Map<Integer, PositionValue> positions) throws IOException {
        if (line.length() > MAX_LINE) throw new EngineException("KataGo 整盘分析响应过长");
        Object parsed = JsonCodec.parse(line);
        if (!(parsed instanceof Map<?, ?> response)) throw new EngineException("KataGo 整盘分析格式无效");
        if (response.containsKey("warning")) return;
        if (response.containsKey("error")) throw new EngineException("KataGo 无法分析这份棋谱");
        if (!id.equals(response.get("id")) || Boolean.TRUE.equals(response.get("isDuringSearch"))) return;
        int turn = integer(response.get("turnNumber"), "turnNumber", 0, expected - 1);
        if (positions.containsKey(turn)) throw new EngineException("KataGo 返回了重复的局面分析");
        Map<?, ?> root = object(response.get("rootInfo"), "rootInfo");
        double rootLead = number(root.get("scoreLead"), "scoreLead", -size * size * 2.0, size * size * 2.0);
        double rootWinrate = number(root.get("winrate"), "winrate", 0, 1);

        if (!(response.get("moveInfos") instanceof List<?> infos) || infos.size() > size * size + 1) {
            throw new EngineException("KataGo 候选落点格式无效");
        }
        Map<String, MoveValue> moves = new HashMap<>();
        MoveValue best = null;
        for (Object item : infos) {
            Map<?, ?> info = object(item, "moveInfo");
            String move = coordinate(info.get("move"), size);
            int order = integer(info.get("order"), "order", 0, size * size);
            double lead = number(info.get("scoreLead"), "scoreLead", -size * size * 2.0, size * size * 2.0);
            double winrate = number(info.get("winrate"), "winrate", 0, 1);
            List<String> variation = variation(info.get("pv"), size);
            MoveValue value = new MoveValue(move, lead, winrate, order, variation);
            moves.merge(move, value, (left, right) -> left.order() <= right.order() ? left : right);
            if (best == null || value.order() < best.order()) best = value;
        }
        positions.put(turn, new PositionValue(rootLead, rootWinrate, moves, best));
    }

    private static Map<?, ?> object(Object value, String field) throws EngineException {
        if (value instanceof Map<?, ?> map) return map;
        throw new EngineException("KataGo 字段无效：" + field);
    }

    private static int integer(Object value, String field, int minimum, int maximum) throws EngineException {
        if (!(value instanceof Number number)) throw new EngineException("KataGo 字段无效：" + field);
        double raw = number.doubleValue();
        int result = number.intValue();
        if (!Double.isFinite(raw) || raw != result || result < minimum || result > maximum) {
            throw new EngineException("KataGo 字段超出范围：" + field);
        }
        return result;
    }

    private static double number(Object value, String field, double minimum, double maximum) throws EngineException {
        if (!(value instanceof Number number)) throw new EngineException("KataGo 字段无效：" + field);
        double result = number.doubleValue();
        if (!Double.isFinite(result) || result < minimum || result > maximum) {
            throw new EngineException("KataGo 字段超出范围：" + field);
        }
        return result;
    }

    private static String coordinate(Object value, int size) throws EngineException {
        if (!(value instanceof String text)) throw new EngineException("KataGo 落点字段无效");
        String result = text.equalsIgnoreCase("pass") ? "PASS" : text.toUpperCase(java.util.Locale.ROOT);
        if (!result.equals("PASS")) {
            try { Point.fromCoordinate(result, size); }
            catch (IllegalArgumentException exception) { throw new EngineException("KataGo 落点超出棋盘", exception); }
        }
        return result;
    }

    private static List<String> variation(Object value, int size) throws EngineException {
        if (!(value instanceof List<?> list)) return List.of();
        List<String> result = new ArrayList<>();
        for (Object item : list) {
            if (result.size() >= 7) break;
            result.add(coordinate(item, size));
        }
        return List.copyOf(result);
    }

    private static void readStdout(Process process, BlockingQueue<String> output,
                                   AtomicBoolean ended, AtomicReference<String> failure) {
        Thread thread = new Thread(() -> {
            long total = 0;
            try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    total += line.getBytes(StandardCharsets.UTF_8).length;
                    if (line.length() > MAX_LINE || total > MAX_OUTPUT) {
                        failure.set("KataGo 整盘分析输出超过限制");
                        break;
                    }
                    if (!output.offer(line)) {
                        failure.set("KataGo 整盘分析输出过快");
                        break;
                    }
                }
            } catch (IOException exception) {
                failure.compareAndSet(null, "KataGo 整盘分析输出中断");
            } finally {
                ended.set(true);
            }
        }, "katago-analysis-stdout");
        thread.setDaemon(true);
        thread.start();
    }

    private static void drain(InputStream stream) {
        Thread thread = new Thread(() -> {
            try (InputStream input = stream) {
                byte[] buffer = new byte[4096];
                while (input.read(buffer) >= 0) { }
            } catch (IOException ignored) { }
        }, "katago-analysis-stderr");
        thread.setDaemon(true);
        thread.start();
    }
}
