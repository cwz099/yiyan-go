package com.yiyan.go.diagnostics;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class AppLogsTest {
    @TempDir Path temporary;

    @Test void credentialsAndFullUrlsAreRedacted() {
        AppLogs.registerSecret("test-private-credential");
        String safe = AppLogs.redact("test-private-credential Bearer other-private-value sk-secretkey "
                + "https://user:password@example.com/private/path?token=hidden");
        for (String secret : new String[]{"test-private-credential", "other-private-value", "sk-secretkey",
                "password", "/private/path", "token=hidden"}) assertFalse(safe.contains(secret), secret);
        assertTrue(safe.contains("example.com"));
    }

    @Test void logFieldsAreAllowlistedAndValidJson() throws Exception {
        String previous = System.getProperty("yiyan.dataDir");
        try {
            System.setProperty("yiyan.dataDir", temporary.toString());
            AppLogs.event("network", "request_failed", Map.of("httpStatus", 401,
                    "requestId", "request-1", "Authorization", "should-never-be-written",
                    "body", "raw-response-must-not-be-written"));
            String content = Files.readString(temporary.resolve("logs/application.jsonl"));
            assertFalse(content.contains("should-never-be-written"));
            assertFalse(content.contains("raw-response-must-not-be-written"));
            Map<?, ?> record = (Map<?, ?>) JsonCodec.parse(content);
            Map<?, ?> fields = (Map<?, ?>) record.get("fields");
            assertEquals(401L, fields.get("httpStatus"));
            assertEquals("request-1", fields.get("requestId"));
            assertEquals("", AppLogs.error());
        } finally {
            if (previous == null) System.clearProperty("yiyan.dataDir");
            else System.setProperty("yiyan.dataDir", previous);
        }
    }

    @Test void rotationRetainsOnlyConfiguredBackups() throws Exception {
        Path current = temporary.resolve("application.jsonl");
        AppLogs.LogWriter writer = new AppLogs.LogWriter(current, 8, 2);
        for (int i = 1; i <= 4; i++) writer.append("{\"n\":" + i + "}");
        assertEquals("{\"n\":4}\n", Files.readString(current));
        assertEquals("{\"n\":3}\n", Files.readString(temporary.resolve("application.1.jsonl")));
        assertEquals("{\"n\":2}\n", Files.readString(temporary.resolve("application.2.jsonl")));
        assertFalse(Files.exists(temporary.resolve("application.3.jsonl")));
    }

    @Test void engineEvidenceSurvivesTheLogAllowlist() throws Exception {
        String previous = System.getProperty("yiyan.dataDir");
        try {
            System.setProperty("yiyan.dataDir", temporary.toString());
            AppLogs.event("engine", "completed", Map.of("engine", "KataGo test", "gtpScore", "B+35.5",
                    "deadStones", 3, "consistent", true, "model", "test-model"));
            Map<?, ?> record = (Map<?, ?>) JsonCodec.parse(Files.readString(temporary.resolve("logs/application.jsonl")));
            Map<?, ?> fields = (Map<?, ?>) record.get("fields");
            assertEquals("KataGo test", fields.get("engine"));
            assertEquals("B+35.5", fields.get("gtpScore"));
            assertEquals(3L, fields.get("deadStones"));
            assertEquals(true, fields.get("consistent"));
        } finally {
            if (previous == null) System.clearProperty("yiyan.dataDir");
            else System.setProperty("yiyan.dataDir", previous);
        }
    }

    @Test void errorMessageNeverIncludesRemoteExceptionText() {
        assertEquals("API 请求超时", AppLogs.safeError(new java.net.http.HttpTimeoutException("private-server-text")));
        assertFalse(AppLogs.safeError(new java.io.IOException("secret-raw-response")).contains("secret"));
    }
}
