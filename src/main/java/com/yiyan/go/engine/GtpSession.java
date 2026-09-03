package com.yiyan.go.engine;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;

/** One private process per adjudication. No shell, bounded output and deadline, cancellable reads. */
final class GtpSession implements AutoCloseable {
    private final Process process;
    private final BufferedWriter input;
    private final BlockingQueue<String> lines = new ArrayBlockingQueue<>(2048);
    private final long deadline;
    private volatile boolean ended;
    private volatile String readError;
    private int nextId;

    GtpSession(ProcessBuilder builder, Duration budget) throws IOException {
        deadline = System.nanoTime() + budget.toNanos();
        builder.environment().remove("DEEPSEEK_API_KEY");
        process = builder.start();
        input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread stdout = new Thread(() -> {
            try (BufferedReader reader = process.inputReader(StandardCharsets.UTF_8)) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.length() > 65536 || !lines.offer(line)) throw new IOException("引擎输出超过限制");
                }
            } catch (IOException e) { readError = "引擎输出中断"; }
            finally { ended = true; }
        }, "katago-stdout");
        stdout.setDaemon(true);
        stdout.start();
        Thread stderr = new Thread(() -> {
            // Drain diagnostics to avoid pipe deadlock; do not echo arbitrary subprocess text or secrets.
            try (InputStream stream = process.getErrorStream()) {
                byte[] bytes = new byte[4096];
                while (stream.read(bytes) >= 0) { }
            } catch (IOException ignored) { }
        }, "katago-stderr");
        stderr.setDaemon(true);
        stderr.start();
    }

    String command(String command) throws IOException, InterruptedException {
        if (command.indexOf('\n') >= 0 || command.indexOf('\r') >= 0) throw new EngineException("无效 GTP 指令");
        if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
        int id = ++nextId;
        input.write(id + " " + command + "\n");
        input.flush();
        String header;
        do { header = nextLine(); } while (header.isBlank());
        String ok = "=" + id, fail = "?" + id;
        boolean success = header.equals(ok) || header.startsWith(ok + " ");
        if (!success && !(header.equals(fail) || header.startsWith(fail + " "))) {
            throw new EngineException("KataGo 响应编号不匹配");
        }
        StringBuilder body = new StringBuilder(header.substring((success ? ok : fail).length()).trim());
        String line;
        while (!(line = nextLine()).isBlank()) {
            if (body.length() + line.length() > 65536) throw new EngineException("KataGo 响应过长");
            if (!body.isEmpty()) body.append('\n');
            body.append(line);
        }
        if (!success) throw new EngineException("KataGo 拒绝指令：" + command.split(" ")[0]);
        return body.toString();
    }

    private String nextLine() throws IOException, InterruptedException {
        for (;;) {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new EngineException("KataGo 判定超时，请重试");
            String line = lines.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(100)), TimeUnit.NANOSECONDS);
            if (line != null) return line;
            if (ended) throw new EngineException(readError != null ? readError : "KataGo 已退出");
        }
    }

    @Override public void close() {
        process.destroy();
        if (process.isAlive()) process.destroyForcibly();
        try { input.close(); } catch (IOException ignored) { }
    }
}
