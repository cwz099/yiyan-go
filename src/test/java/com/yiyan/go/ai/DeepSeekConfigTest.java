package com.yiyan.go.ai;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DeepSeekConfigTest {
    @Test void rejectsCredentialsEmbeddedInEndpoint() {
        for (String endpoint : new String[]{"https://user:password@example.com/chat/completions",
                "https://example.com/chat/completions?token=secret", "https://example.com/chat/completions#secret"}) {
            IllegalArgumentException error = assertThrows(IllegalArgumentException.class,
                    () -> DeepSeekConfig.of("test-config-key", endpoint, "test-model"));
            assertFalse(error.getMessage().contains("secret"));
            assertFalse(error.getMessage().contains("password"));
        }
    }

    @Test void configDescriptionDoesNotDiscloseCredentialsOrPath() {
        var config = DeepSeekConfig.of("test-config-key", "https://example.com/private/path", "test-model");
        assertTrue(config.toString().contains("example.com"));
        assertFalse(config.toString().contains("test-config-key"));
        assertFalse(config.toString().contains("private/path"));
    }
}
