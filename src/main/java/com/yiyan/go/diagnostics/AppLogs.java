package com.yiyan.go.diagnostics;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;

/** Best-effort, bounded diagnostics. No raw request, response or exception text is retained. */
public final class AppLogs {
    private static final Set<String> ALLOWED = Set.of("requestId", "gameId", "model", "moveNumber", "host",
            "httpStatus", "durationMs", "responseBytes", "parseSuccess", "action", "coordinate", "errorCode",
            "errorClass", "source", "reason", "status", "boardSize", "playerColor", "komi", "outcome",
            "result", "count", "pathType", "version", "selectedColor", "captured", "undoCount", "enabled",
            "winner", "margin", "operation", "fileCount", "recordCount", "blackCaptures", "whiteCaptures",
            "decisionId", "attempt", "attempts", "maxAttempts", "willRetry", "validationReason", "intent",
            "engine", "gtpScore", "deadStones", "consistent", "difficulty", "maxVisits", "maxTimeMs",
            "temperature", "historyMoves", "syncMode", "engineSessionId", "opponentMode",
            "humanMistakes", "opportunities");
    private static final Set<String> SECRETS = ConcurrentHashMap.newKeySet();
    private static final String SESSION = UUID.randomUUID().toString();
    private static volatile String failure = "";
    private static LogWriter writer;
    private AppLogs() { }

    public static void registerSecret(String secret) {
        if (secret != null && !secret.isBlank()) SECRETS.add(secret);
    }

    public static String redact(String text) {
        if (text == null) return "";
        String safe = text;
        for (String secret : SECRETS) safe = safe.replace(secret, "[redacted]");
        safe = safe.replaceAll("(?i)\\bBearer\\s+[^\\s\"',;]+", "Bearer [redacted]");
        safe = safe.replaceAll("(?i)\\bsk-[A-Za-z0-9_-]+", "[redacted]");
        // URLs may carry credentials, signed paths or query parameters: retain only their hosts.
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("https?://[^\\s\"<>]+", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(safe);
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String host;
            try { host = safeHost(URI.create(matcher.group())); }
            catch (IllegalArgumentException exception) { host = "[redacted-url]"; }
            matcher.appendReplacement(result, java.util.regex.Matcher.quoteReplacement(host));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static String safeHost(URI endpoint) {
        if (endpoint == null || endpoint.getHost() == null) return "unknown-host";
        String host = endpoint.getHost();
        for (String secret : SECRETS) host = host.replace(secret, "[redacted]");
        return host;
    }

    public static synchronized void event(String category, String event, Map<String, ?> fields) {
        try {
            Path path = AppPaths.dataDirectory().resolve("logs").resolve("application.jsonl");
            if (writer == null || !writer.path.equals(path)) writer = new LogWriter(path, 1024 * 1024, 3);
            Map<String, Object> safe = new LinkedHashMap<>();
            if (fields != null) fields.forEach((key, value) -> {
                if (ALLOWED.contains(key) && safe.size() < 64) {
                    if (value == null || value instanceof Boolean || value instanceof Number) safe.put(key, value);
                    else if (value instanceof String string) safe.put(key, limited(redact(string)));
                }
            });
            Map<String, Object> record = new LinkedHashMap<>();
            record.put("schema", 1);
            record.put("time", Instant.now().toString());
            record.put("session", SESSION);
            record.put("category", limited(redact(category)));
            record.put("event", limited(redact(event)));
            record.put("fields", safe);
            writer.append(JsonCodec.stringify(record));
            failure = "";
        } catch (IOException | RuntimeException exception) {
            failure = "日志暂不可写（" + exception.getClass().getSimpleName() + "）";
        }
    }

    private static String limited(String text) { return text.length() > 512 ? text.substring(0, 512) + "…" : text; }
    public static String error() { return failure; }

    public static String errorCode(Throwable error) {
        Throwable cause = unwrap(error);
        if (cause instanceof DiagnosticException diagnostic) return diagnostic.code();
        if (cause instanceof com.yiyan.go.engine.EngineException) {
            return cause.getMessage() != null && cause.getMessage().contains("超时")
                    ? "engine_timeout" : "engine_failure";
        }
        if (cause instanceof HttpTimeoutException || cause instanceof TimeoutException) return "timeout";
        if (cause instanceof InterruptedException || cause instanceof CancellationException) return "cancelled";
        if (cause instanceof java.net.ConnectException) return "connection_failed";
        if (cause instanceof javax.net.ssl.SSLException) return "tls_error";
        if (cause instanceof IOException) return "network_error";
        return "unexpected_error";
    }

    public static Throwable unwrap(Throwable error) {
        Throwable cause = error;
        for (int i = 0; i < 12 && cause != null; i++) {
            if ((cause instanceof java.util.concurrent.ExecutionException || cause instanceof java.util.concurrent.CompletionException)
                    && cause.getCause() != null) cause = cause.getCause();
            else break;
        }
        return cause;
    }

    public static String safeError(Throwable error) {
        String code = errorCode(error);
        if (code.equals("engine_failure") && unwrap(error) instanceof com.yiyan.go.engine.EngineException engine) {
            return limited(redact(engine.getMessage()));
        }
        if (code.equals("unsafe_move") && unwrap(error) instanceof DiagnosticException diagnostic
                && !diagnostic.reason().isBlank()) {
            return limited(redact(diagnostic.reason()));
        }
        if (code.startsWith("http_")) {
            return switch (code) {
                case "http_401", "http_403" -> "API 身份验证或权限失败（HTTP " + code.substring(5) + "）";
                case "http_429" -> "请求过于频繁或账户额度受限（HTTP 429）";
                default -> "API 服务返回错误（HTTP " + code.substring(5) + "）";
            };
        }
        return switch (code) {
            case "timeout" -> "API 请求超时";
            case "engine_timeout" -> "KataGo 计算超时，请重试";
            case "cancelled" -> "请求已取消";
            case "invalid_json" -> "API 返回的数据格式无效";
            case "invalid_move" -> "AI 返回了不合法的落点";
            case "unsafe_move" -> "AI 落点未通过基础安全校验";
            case "unreasonable_pass" -> "AI 停一手未通过局面校验";
            case "truncated_response" -> "AI 回复达到长度上限，未完整返回";
            case "response_too_large" -> "API 响应超出安全大小限制";
            case "connection_failed" -> "无法连接 API 服务";
            case "tls_error" -> "API 安全连接验证失败";
            case "network_error" -> "API 网络传输失败";
            default -> "请求处理失败";
        };
    }

    static final class LogWriter {
        private final Path path;
        private final long maxBytes;
        private final int backups;
        LogWriter(Path path, long maxBytes, int backups) {
            if (maxBytes < 1 || backups < 1) throw new IllegalArgumentException("Invalid log limits");
            this.path = path; this.maxBytes = maxBytes; this.backups = backups;
        }
        synchronized void append(String record) throws IOException {
            Files.createDirectories(path.getParent());
            byte[] bytes = (record + "\n").getBytes(StandardCharsets.UTF_8);
            if (Files.exists(path) && Files.size(path) > 0 && Files.size(path) + bytes.length > maxBytes) {
                for (int i = backups - 1; i >= 1; i--) {
                    Path older = rotated(i);
                    if (Files.exists(older)) Files.move(older, rotated(i + 1), StandardCopyOption.REPLACE_EXISTING);
                }
                Files.move(path, rotated(1), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.write(path, bytes, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        }
        private Path rotated(int index) { return path.resolveSibling("application." + index + ".jsonl"); }
    }
}
