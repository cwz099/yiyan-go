package com.yiyan.go.ai;

import com.yiyan.go.diagnostics.AppLogs;
import com.yiyan.go.diagnostics.DiagnosticException;
import com.yiyan.go.diagnostics.JsonCodec;
import com.yiyan.go.game.BoardState;
import com.yiyan.go.game.Point;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSession;
import java.io.IOException;
import java.net.Authenticator;
import java.net.CookieHandler;
import java.net.ProxySelector;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.*;

/** Exercises the real request/response path without contacting an API or using credentials. */
final class DeepSeekNetworkTest {
    private static final String KEY = "network-test-private-credential-81234";
    @TempDir Path dataDirectory;
    private String previousDataDirectory;

    @BeforeEach void isolateLogs() {
        previousDataDirectory = System.getProperty("yiyan.dataDir");
        System.setProperty("yiyan.dataDir", dataDirectory.toString());
    }

    @AfterEach void restoreLogs() {
        if (previousDataDirectory == null) System.clearProperty("yiyan.dataDir");
        else System.setProperty("yiyan.dataDir", previousDataDirectory);
    }

    @Test void successfulResponseProducesLegalMoveAndRedactsMotivation() throws Exception {
        String content = JsonCodec.stringify(Map.of("move", "D4", "motivation", "保持联络 " + KEY));
        String envelope = JsonCodec.stringify(Map.of("choices", List.of(Map.of("message", Map.of("content", content)))));
        MockClient client = new MockClient(200, envelope, false);
        AiDecision decision = opponent(client, Duration.ofSeconds(2)).chooseMove(new BoardState(9));

        assertFalse(decision.pass());
        assertEquals(Point.fromCoordinate("D4", 9), decision.point());
        assertFalse(decision.motivation().contains(KEY));
        assertFalse(decision.requestId().isBlank());
        assertEquals(1, decision.attempts());
        assertEquals("Bearer " + KEY, client.request.headers().firstValue("Authorization").orElseThrow());
        String log = logText();
        assertTrue(log.contains("request_succeeded"));
        assertTrue(log.contains("\"httpStatus\":200"));
        assertFalse(log.contains(KEY));
        assertFalse(log.contains("保持联络"), "Raw response text must not be logged");
    }

    @Test void unauthorizedResponseHasSafeClassificationWithoutBodyInLogs() throws Exception {
        String privateBody = "private-server-error-details-78321 " + KEY;
        MockClient client = new MockClient(401, privateBody, false);
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> opponent(client, Duration.ofSeconds(2)).chooseMove(new BoardState(9)));

        assertEquals("http_401", error.code());
        assertEquals(1, client.requests.size(), "Authentication failures must not be retried");
        assertTrue(AppLogs.safeError(error).contains("401"));
        String log = logText();
        assertTrue(log.contains("request_failed"));
        assertTrue(log.contains("\"httpStatus\":401"));
        assertTrue(log.contains("\"errorCode\":\"http_401\""));
        assertFalse(log.contains("private-server-error-details-78321"));
        assertFalse(log.contains(KEY));
    }

    @Test void occupiedCoordinateIsCorrectedByModelWithoutChangingItsChosenPoint() throws Exception {
        BoardState board = new BoardState(9);
        Point occupied = Point.fromCoordinate("D4", 9);
        board.play(occupied.x(), occupied.y());
        MockClient client = new MockClient(reply(200, answer("D4")), reply(200, answer("F6")));
        AiDecision result = opponent(client, Duration.ofSeconds(2)).chooseMove(board);
        assertEquals(Point.fromCoordinate("F6", 9), result.point());
        assertEquals(2, result.attempts());
        assertEquals(2, client.requests.size());
        String correctionBody = requestBody(client.requests.get(1));
        assertTrue(correctionBody.contains("已有棋子"));
        assertTrue(correctionBody.contains("全部合法落点"));
        assertTrue(correctionBody.contains("本轮实际允许的安全落点清单"));
        assertTrue(correctionBody.contains("上一手：黑棋 D4"));
        assertTrue(logText().contains("occupied_point"));
        assertTrue(logText().contains("\"attempt\":2"));
    }

    @Test void malformedJsonAndPrematurePassAreCorrectedWithinThreeRequests() throws Exception {
        MockClient client = new MockClient(reply(200, "not-json"), reply(200, answer("PASS")), reply(200, answer("D4")));
        AiDecision result = opponent(client, Duration.ofSeconds(2)).chooseMove(new BoardState(9));
        assertEquals(3, result.attempts());
        assertFalse(result.pass());
        assertTrue(logText().contains("invalid_json"));
        assertTrue(logText().contains("unreasonable_pass"));
    }

    @Test void invalidCoordinatesStopAfterThreeAttemptsAndDoNotLeakResponse() throws Exception {
        MockClient client = new MockClient(200, answer(KEY), false);
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> opponent(client, Duration.ofSeconds(2)).chooseMove(new BoardState(9)));
        assertEquals("invalid_move", error.code());
        assertEquals(3, client.requests.size());
        assertTrue(logText().contains("\"willRetry\":false"));
        assertFalse(logText().contains(KEY));
        assertFalse(logText().contains("\"coordinate\":"));
    }

    @Test void transientServerFailureCanRecover() throws Exception {
        MockClient client = new MockClient(reply(500, "private-body " + KEY), reply(200, answer("D4")));
        assertEquals(2, opponent(client, Duration.ofSeconds(2)).chooseMove(new BoardState(9)).attempts());
        assertTrue(logText().contains("http_500"));
        assertFalse(logText().contains("private-body"));
    }

    @Test void unprofitableSelfAtariIsSentBackForModelCorrection() throws Exception {
        BoardState board = new BoardState(9);
        Point black = Point.fromCoordinate("B9", 9);
        board.play(black.x(), black.y());
        MockClient client = new MockClient(reply(200, answer("A9")), reply(200, answer("F6")));
        AiDecision result = opponent(client, Duration.ofSeconds(2)).chooseMove(board);
        assertEquals(Point.fromCoordinate("F6", 9), result.point());
        assertEquals(2, result.attempts());
        assertTrue(logText().contains("self_atari"));
    }

    @Test void truncatedResponseIsRetriedWithLargerTokenBudget() throws Exception {
        String truncated = JsonCodec.stringify(Map.of("choices", List.of(Map.of("finish_reason", "length",
                "message", Map.of("content", "{incomplete")))));
        MockClient client = new MockClient(reply(200, truncated), reply(200, answer("D4")));
        assertEquals(2, opponent(client, Duration.ofSeconds(2)).chooseMove(new BoardState(9)).attempts());
        assertTrue(logText().contains("truncated_response"));
        assertTrue(requestBody(client.requests.get(0)).contains("\"max_tokens\":512"));
        assertFalse(requestBody(client.requests.get(0)).contains("Q10"), "Do not anchor 9x9 decisions to an invalid fixed example");
    }

    @Test void cancellationIsNotRetried() throws Exception {
        MockClient client = new MockClient(200, "", true);
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedException.class,
                    () -> opponent(client, Duration.ofSeconds(2)).chooseMove(new BoardState(9)));
            assertEquals(0, client.requests.size());
        } finally { Thread.interrupted(); }
    }

    private static Reply reply(int status, String body) { return new Reply(status, body, false); }
    private static String answer(String move) {
        return JsonCodec.stringify(Map.of("choices", List.of(Map.of("finish_reason", "stop", "message",
                Map.of("content", JsonCodec.stringify(Map.of("move", move, "motivation", "白棋优势明显 " + KEY)))))));
    }

    private static String requestBody(HttpRequest request) {
        StringBuilder result = new StringBuilder();
        request.bodyPublisher().orElseThrow().subscribe(new Flow.Subscriber<ByteBuffer>() {
            @Override public void onSubscribe(Flow.Subscription subscription) { subscription.request(Long.MAX_VALUE); }
            @Override public void onNext(ByteBuffer item) {
                byte[] bytes = new byte[item.remaining()]; item.get(bytes);
                result.append(new String(bytes, StandardCharsets.UTF_8));
            }
            @Override public void onError(Throwable error) { throw new AssertionError(error); }
            @Override public void onComplete() { }
        });
        return result.toString();
    }

    @Test void pendingResponseTimesOutAndCancelsFuture() throws Exception {
        MockClient client = new MockClient(200, "", true);
        DiagnosticException error = assertThrows(DiagnosticException.class,
                () -> opponent(client, Duration.ofMillis(150)).chooseMove(new BoardState(9)));

        assertEquals("timeout", error.code());
        assertNotNull(client.pending);
        assertTrue(client.pending.isCancelled());
        String log = logText();
        assertTrue(log.contains("request_failed"));
        assertTrue(log.contains("\"errorCode\":\"timeout\""));
        assertFalse(log.contains(KEY));
    }

    private DeepSeekOpponent opponent(MockClient client, Duration timeout) {
        DeepSeekConfig config = new DeepSeekConfig(KEY, URI.create("https://api.example.test/chat/completions"),
                "test-model", timeout);
        return new DeepSeekOpponent(config, "network-test-game", client);
    }

    private String logText() throws IOException {
        assertEquals("", AppLogs.error());
        return Files.readString(dataDirectory.resolve("logs/application.jsonl"));
    }

    private record Reply(int status, String body, boolean stall) { }

    private static final class MockClient extends HttpClient {
        private final HttpClient configuration = HttpClient.newHttpClient();
        private final List<Reply> replies;
        private final List<HttpRequest> requests = new ArrayList<>();
        private HttpRequest request;
        private CompletableFuture<?> pending;

        MockClient(int status, String body, boolean stall) {
            this(new Reply(status, body, stall));
        }

        MockClient(Reply... replies) { this.replies = List.of(replies); }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler) {
            this.request = request;
            Reply reply = replies.get(Math.min(requests.size(), replies.size() - 1));
            requests.add(request);
            int status = reply.status();
            byte[] body = reply.body().getBytes(StandardCharsets.UTF_8);
            if (reply.stall()) {
                CompletableFuture<HttpResponse<T>> future = new CompletableFuture<>();
                pending = future;
                return future;
            }
            HttpHeaders headers = HttpHeaders.of(Map.of("Content-Type", List.of("application/json")), (a, b) -> true);
            HttpResponse.BodySubscriber<T> subscriber = handler.apply(new HttpResponse.ResponseInfo() {
                @Override public int statusCode() { return status; }
                @Override public HttpHeaders headers() { return headers; }
                @Override public Version version() { return Version.HTTP_1_1; }
            });
            subscriber.onSubscribe(new Flow.Subscription() {
                @Override public void request(long n) { }
                @Override public void cancel() { }
            });
            subscriber.onNext(List.of(ByteBuffer.wrap(body)));
            subscriber.onComplete();
            CompletableFuture<HttpResponse<T>> future = subscriber.getBody().toCompletableFuture().thenApply(
                    value -> new HttpResponse<T>() {
                        @Override public int statusCode() { return status; }
                        @Override public HttpRequest request() { return request; }
                        @Override public Optional<HttpResponse<T>> previousResponse() { return Optional.empty(); }
                        @Override public HttpHeaders headers() { return headers; }
                        @Override public T body() { return value; }
                        @Override public Optional<SSLSession> sslSession() { return Optional.empty(); }
                        @Override public URI uri() { return request.uri(); }
                        @Override public Version version() { return Version.HTTP_1_1; }
                    });
            pending = future;
            return future;
        }

        @Override public <T> CompletableFuture<HttpResponse<T>> sendAsync(HttpRequest request,
                HttpResponse.BodyHandler<T> handler, HttpResponse.PushPromiseHandler<T> push) {
            return sendAsync(request, handler);
        }
        @Override public <T> HttpResponse<T> send(HttpRequest request, HttpResponse.BodyHandler<T> handler) {
            throw new AssertionError("Only asynchronous requests are expected");
        }
        @Override public Optional<CookieHandler> cookieHandler() { return configuration.cookieHandler(); }
        @Override public Optional<Duration> connectTimeout() { return configuration.connectTimeout(); }
        @Override public Redirect followRedirects() { return configuration.followRedirects(); }
        @Override public Optional<ProxySelector> proxy() { return configuration.proxy(); }
        @Override public SSLContext sslContext() { return configuration.sslContext(); }
        @Override public SSLParameters sslParameters() { return configuration.sslParameters(); }
        @Override public Optional<Authenticator> authenticator() { return configuration.authenticator(); }
        @Override public Version version() { return configuration.version(); }
        @Override public Optional<Executor> executor() { return configuration.executor(); }
    }
}
