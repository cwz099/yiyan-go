package com.yiyan.go.ai;

import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.Point;
import com.yiyan.go.game.Stone;
import com.yiyan.go.diagnostics.AppLogs;
import com.yiyan.go.diagnostics.DiagnosticException;
import com.yiyan.go.diagnostics.JsonCodec;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

public final class DeepSeekOpponent implements GoOpponent {
    private static final int MAX_RESPONSE_BYTES = 1024 * 1024;
    private static final int MAX_ATTEMPTS = 3;
    private final DeepSeekConfig config;
    private final HttpClient client;
    private final String gameId;

    public DeepSeekOpponent(DeepSeekConfig config) {
        this(config, null);
    }

    public DeepSeekOpponent(DeepSeekConfig config, String gameId) {
        this(config, gameId, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(12)).build());
    }

    DeepSeekOpponent(DeepSeekConfig config, String gameId, HttpClient client) {
        this.config = config;
        this.gameId = gameId;
        this.client = client;
        AppLogs.registerSecret(config.apiKey());
    }

    @Override
    public AiDecision chooseMove(BoardState position) throws IOException, InterruptedException {
        String decisionId = UUID.randomUUID().toString();
        Duration budget = config.timeout().compareTo(Duration.ofSeconds(45)) > 0
                ? Duration.ofSeconds(90) : config.timeout().multipliedBy(2);
        long deadline = System.nanoTime() + budget.toNanos();
        String correction = "";
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            try {
                return chooseAttempt(position, correction, decisionId, attempt, deadline);
            } catch (IOException exception) {
                if (!canRetry(attempt, AppLogs.errorCode(exception), deadline)) throw exception;
                String reason = exception instanceof DiagnosticException diagnostic && !diagnostic.reason().isBlank()
                        ? diagnostic.reason() : AppLogs.safeError(exception);
                correction = "上一请求未被采纳：" + reason + "。棋盘没有发生变化，请重新阅读合法落点清单后自主选择，返回完整 JSON。";
                if (!isValidationFailure(AppLogs.errorCode(exception))) Thread.sleep(200L * attempt);
            }
        }
        throw new DiagnosticException("unexpected_error");
    }

    private static boolean isValidationFailure(String code) {
        return code.equals("invalid_move") || code.equals("unsafe_move") || code.equals("invalid_json") || code.equals("unreasonable_pass")
                || code.equals("truncated_response");
    }

    private static boolean retryable(String code) {
        return isValidationFailure(code) || code.equals("timeout") || code.equals("connection_failed")
                || code.equals("network_error") || code.equals("http_429") || code.equals("http_500")
                || code.equals("http_502") || code.equals("http_503") || code.equals("http_504");
    }

    private static boolean canRetry(int attempt, String code, long deadline) {
        long minimumTime = TimeUnit.MILLISECONDS.toNanos(isValidationFailure(code) ? 1 : 200L * attempt + 1);
        return attempt < MAX_ATTEMPTS && retryable(code) && deadline - System.nanoTime() > minimumTime;
    }

    private AiDecision chooseAttempt(BoardState position, String correction, String decisionId, int attempt, long deadline)
            throws IOException, InterruptedException {
        long start = System.nanoTime();
        AtomicInteger status = new AtomicInteger();
        AtomicInteger responseBytes = new AtomicInteger();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("requestId", UUID.randomUUID().toString());
        fields.put("decisionId", decisionId);
        fields.put("attempt", attempt);
        fields.put("maxAttempts", MAX_ATTEMPTS);
        if (gameId != null) fields.put("gameId", gameId);
        fields.put("model", config.model());
        fields.put("host", AppLogs.safeHost(config.endpoint()));
        fields.put("moveNumber", position.moveNumber());
        fields.put("parseSuccess", false);
        AppLogs.event("network", "request_started", fields);
        CompletableFuture<HttpResponse<byte[]>> pending = null;
        try {
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            String requestJson = buildRequest(position, correction);
            if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new DiagnosticException("timeout");
            Duration requestTimeout = config.timeout().compareTo(Duration.ofNanos(remaining)) < 0
                    ? config.timeout() : Duration.ofNanos(remaining);
            HttpRequest request = HttpRequest.newBuilder(config.endpoint())
                    .timeout(requestTimeout)
                    .header("Authorization", "Bearer " + config.apiKey())
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestJson, StandardCharsets.UTF_8))
                    .build();
            pending = client.sendAsync(request, info -> {
                status.set(info.statusCode());
                return new LimitedBodySubscriber(responseBytes);
            });
            HttpResponse<byte[]> response;
            try {
                // Unlike an InputStream response this deadline also covers a stalled response body.
                response = pending.get(Math.max(1L, requestTimeout.toNanos()), TimeUnit.NANOSECONDS);
            } catch (TimeoutException exception) {
                throw new DiagnosticException("timeout");
            } catch (ExecutionException exception) {
                Throwable cause = AppLogs.unwrap(exception);
                if (cause instanceof IOException io) throw io;
                throw new DiagnosticException(AppLogs.errorCode(cause));
            }
            status.set(response.statusCode());
            responseBytes.set(response.body().length);
            if (status.get() < 200 || status.get() >= 300) {
                throw new DiagnosticException("http_" + status.get(), status.get());
            }
            Map<?, ?> decision = parseDecision(new String(response.body(), StandardCharsets.UTF_8));
            String moveText = (String) decision.get("move");
            String motivation = (String) decision.get("motivation");
            String requestedIntent = decision.get("intent") instanceof String value ? value : "";
            String intent = switch (requestedIntent) {
                case "capture", "connect", "defend", "develop", "probe" -> requestedIntent;
                default -> "";
            };
            fields.put("intent", intent);
            fields.put("parseSuccess", true);
            String normalized = moveText.trim().toUpperCase(Locale.ROOT);
            AiDecision result;
            if (normalized.equals("PASS") || normalized.equals("停一手")) {
                fields.put("action", "pass");
                MovePolicy.PassAssessment assessment = MovePolicy.assessPass(position);
                if (!assessment.allowed()) throw new DiagnosticException("unreasonable_pass", null, assessment.reason());
                result = AiDecision.pass("DeepSeek 选择停一手。" + assessment.reason());
            } else {
                fields.put("action", "play");
                Point point;
                try {
                    if (!normalized.matches("[A-HJ-T](?:[1-9]|1[0-9])")) throw new IllegalArgumentException();
                    point = Point.fromCoordinate(normalized, position.size());
                } catch (IllegalArgumentException exception) {
                    fields.put("validationReason", "invalid_coordinate");
                    throw new DiagnosticException("invalid_move", null, "坐标格式错误或超出棋盘范围，列坐标不包含 I");
                }
                fields.put("coordinate", point.coordinate(position.size()));
                if (!position.isLegal(point.x(), point.y())) {
                    boolean occupied = position.stoneAt(point.x(), point.y()) != Stone.EMPTY;
                    fields.put("validationReason", occupied ? "occupied_point" : "suicide_or_ko");
                    throw new DiagnosticException("invalid_move", null, "落点 " + point.coordinate(position.size())
                            + (occupied ? " 已有棋子，不能重复落子" : " 不符合禁自杀或劫规则"));
                }
                MovePolicy.Assessment assessment = MovePolicy.analyze(position, point);
                if (assessment.captured() == 0 && (assessment.fillsOwnEye() || assessment.selfAtari())) {
                    fields.put("validationReason", assessment.fillsOwnEye() ? "fills_own_eye" : "self_atari");
                    throw new DiagnosticException("unsafe_move", null, "落点 " + point.coordinate(position.size())
                            + (assessment.fillsOwnEye() ? " 会填入己方眼位，且不能提子"
                            : " 会使棋块仅剩一口气，且不能提子"));
                }
                result = AiDecision.play(point, MovePolicy.explainModelChoice(position, point, intent, limitMotivation(motivation)));
            }
            finishFields(fields, start, status.get(), responseBytes.get());
            AppLogs.event("network", "request_succeeded", fields);
            return result.withRequest((String) fields.get("requestId"), attempt);
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (pending != null) pending.cancel(true);
            finishFields(fields, start, status.get(), responseBytes.get());
            fields.put("errorCode", AppLogs.errorCode(exception));
            fields.put("willRetry", canRetry(attempt, AppLogs.errorCode(exception), deadline));
            fields.put("errorClass", AppLogs.unwrap(exception).getClass().getSimpleName());
            AppLogs.event("network", "request_failed", fields);
            throw exception;
        }
    }

    private static void finishFields(Map<String, Object> fields, long start, int status, int bytes) {
        fields.put("durationMs", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start));
        if (status != 0) fields.put("httpStatus", status);
        fields.put("responseBytes", bytes);
    }

    private static Map<?, ?> parseDecision(String response) throws DiagnosticException {
        try {
            if (!(JsonCodec.parse(response) instanceof Map<?, ?> envelope)
                    || !(envelope.get("choices") instanceof List<?> choices) || choices.isEmpty()
                    || !(choices.get(0) instanceof Map<?, ?> choice)) {
                throw new DiagnosticException("invalid_json");
            }
            if ("length".equals(choice.get("finish_reason"))) throw new DiagnosticException("truncated_response");
            if (!(choice.get("message") instanceof Map<?, ?> message)
                    || !(message.get("content") instanceof String content)
                    || !(JsonCodec.parse(stripCodeFence(content)) instanceof Map<?, ?> decision)
                    || !(decision.get("move") instanceof String)
                    || !(decision.get("motivation") instanceof String motivation) || motivation.isBlank()) {
                throw new DiagnosticException("invalid_json");
            }
            return decision;
        } catch (DiagnosticException exception) { throw exception; }
        catch (IOException exception) { throw new DiagnosticException("invalid_json"); }
    }

    private static final class LimitedBodySubscriber implements HttpResponse.BodySubscriber<byte[]> {
        private final CompletableFuture<byte[]> result = new CompletableFuture<>();
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final AtomicInteger byteCount;
        private Flow.Subscription subscription;
        LimitedBodySubscriber(AtomicInteger byteCount) { this.byteCount = byteCount; }
        @Override public CompletionStage<byte[]> getBody() { return result; }
        @Override public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(1);
        }
        @Override public void onNext(List<ByteBuffer> items) {
            for (ByteBuffer item : items) {
                int count = item.remaining();
                if ((long) buffer.size() + count > MAX_RESPONSE_BYTES) {
                    byteCount.set(MAX_RESPONSE_BYTES + 1);
                    subscription.cancel();
                    result.completeExceptionally(new DiagnosticException("response_too_large"));
                    return;
                }
                byte[] chunk = new byte[count];
                item.get(chunk);
                buffer.writeBytes(chunk);
                byteCount.addAndGet(count);
            }
            subscription.request(1);
        }
        @Override public void onError(Throwable error) { result.completeExceptionally(error); }
        @Override public void onComplete() { result.complete(buffer.toByteArray()); }
    }

    private String buildRequest(BoardState position, String correction) {
        String system = "你是一位沉静、友善的围棋对手。你执" + position.turn().chineseName()
                + "。根据当前 " + position.size() + " 路棋盘选择一个合法落点，并给玩家一段不超过 80 个汉字的战略摘要。"
                + "摘要只说明这手棋的可见目的，不展示隐藏思维过程。"
                + "说明请简短交代目标与风险；没有可靠估分依据时，不要宣称领先、优势明显或获胜。"
                + "若停一手，请解释具体原因，不要虚构棋盘已满。"
                + "避免无提子收益的填己方真眼或落子后只剩一口气。"
                + "只返回一个 JSON 对象，不要 Markdown，不要额外文字。"
                + "对象必须包含两个字符串字段：move（合法清单中的坐标或获准的 PASS）、motivation（简短说明）。"
                + "另请给出 intent 字段，值限 capture（提子）、connect（联络）、defend（补强）、develop（发展）、probe（试探）。"
                + "列坐标跳过字母 I；若决定停一手，move 写 PASS。";
        MovePolicy.PassAssessment pass = MovePolicy.assessPass(position);
        List<Point> legalPoints = position.legalPoints();
        String legal = String.join(",", legalPoints.stream().map(point -> point.coordinate(position.size())).toList());
        List<String> safePoints = new ArrayList<>();
        for (Point point : legalPoints) {
            if (Thread.currentThread().isInterrupted()) break;
            MovePolicy.Assessment assessment = MovePolicy.analyze(position, point);
            if (assessment.captured() > 0 || (!assessment.fillsOwnEye() && !assessment.selfAtari())) {
                safePoints.add(point.coordinate(position.size()));
            }
        }
        String previous = position.lastMove() == null ? "无，棋局尚未落子"
                : position.lastMove().stone().chineseName() + " " + position.lastMove().coordinate(position.size());
        String user = "当前轮到" + position.turn().chineseName() + "，已进行 "
                + position.moveNumber() + " 手。上一手：" + previous
                + "。黑提 " + position.blackCaptures() + "，白提 " + position.whiteCaptures()
                + "，白贴 " + position.komi() + " 目（提子数不是胜负判定）。"
                + "\n完整棋盘中 X=黑，O=白，.=空：\n" + position.asciiDiagram()
                + "\n经规则校验的全部合法落点（不得选择清单之外的坐标）：\n" + legal
                + "\n本轮实际允许的安全落点清单（已剔除无提子收益的填己方真眼、自紧至一口气；请从本清单选点）：\n"
                + String.join(",", safePoints)
                + "\n停一手校验：" + (pass.allowed() ? "允许" : "本轮不允许") + "。" + pass.reason();
        List<Map<String, String>> messages = new ArrayList<>();
        messages.add(Map.of("role", "system", "content", system));
        messages.add(Map.of("role", "user", "content", user));
        if (!correction.isBlank()) messages.add(Map.of("role", "user", "content", correction));
        return JsonCodec.stringify(Map.of("model", config.model(), "thinking", Map.of("type", "disabled"),
                "temperature", 0.35, "max_tokens", 512, "response_format", Map.of("type", "json_object"),
                "messages", messages));
    }

    private static String limitMotivation(String text) {
        String clean = AppLogs.redact(text).trim().replaceAll("\\s+", " ");
        return clean.length() <= 180 ? clean : clean.substring(0, 180) + "…";
    }

    private static String stripCodeFence(String text) {
        String clean = text.trim();
        if (clean.startsWith("```")) {
            int firstLine = clean.indexOf('\n');
            int lastFence = clean.lastIndexOf("```");
            if (firstLine >= 0 && lastFence > firstLine) {
                clean = clean.substring(firstLine + 1, lastFence).trim();
            }
        }
        return clean;
    }

    private static String escape(String value) {
        StringBuilder result = new StringBuilder(value.length() + 32);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\' -> result.append("\\\\");
                case '"' -> result.append("\\\"");
                case '\n' -> result.append("\\n");
                case '\r' -> result.append("\\r");
                case '\t' -> result.append("\\t");
                default -> {
                    if (c < 0x20) result.append(String.format("\\u%04x", (int) c));
                    else result.append(c);
                }
            }
        }
        return result.toString();
    }

    static String extractJsonString(String json, String field) throws IOException {
        if (json == null) return null;
        String needle = "\"" + field + "\"";
        int from = 0;
        while (true) {
            int fieldIndex = json.indexOf(needle, from);
            if (fieldIndex < 0) return null;
            int colon = json.indexOf(':', fieldIndex + needle.length());
            if (colon < 0) return null;
            int quote = colon + 1;
            while (quote < json.length() && Character.isWhitespace(json.charAt(quote))) quote++;
            if (quote >= json.length() || json.charAt(quote) != '"') {
                from = fieldIndex + needle.length();
                continue;
            }
            return readJsonString(json, quote);
        }
    }

    private static String readJsonString(String json, int openingQuote) throws IOException {
        StringBuilder result = new StringBuilder();
        for (int i = openingQuote + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"') return result.toString();
            if (c != '\\') {
                result.append(c);
                continue;
            }
            if (++i >= json.length()) break;
            char escaped = json.charAt(i);
            switch (escaped) {
                case '"', '\\', '/' -> result.append(escaped);
                case 'b' -> result.append('\b');
                case 'f' -> result.append('\f');
                case 'n' -> result.append('\n');
                case 'r' -> result.append('\r');
                case 't' -> result.append('\t');
                case 'u' -> {
                    if (i + 4 >= json.length()) throw new IOException("无效的 JSON Unicode 转义");
                    try {
                        result.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16));
                    } catch (NumberFormatException exception) {
                        throw new IOException("无效的 JSON Unicode 转义", exception);
                    }
                    i += 4;
                }
                default -> throw new IOException("无效的 JSON 转义: \\" + escaped);
            }
        }
        throw new IOException("JSON 字符串没有结束");
    }

    @Override
    public String displayName() {
        return "DeepSeek · " + AppLogs.redact(config.model());
    }
}
