package com.yiyan.go.diagnostics;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class JsonCodecTest {
    @Test void roundTripEscapesChineseAndControlCharacters() throws Exception {
        Map<String, String> original = Map.of("说明", "连络\n\"安全\"\t\\\u0001");
        assertEquals(original, JsonCodec.parse(JsonCodec.stringify(original)));
        assertEquals("A", JsonCodec.parse("\"\\u0041\""));
    }

    @Test void rejectsMalformedJsonAndUnicode() {
        for (String value : new String[]{"{\"a\":1,\"a\":2}", "[1,]", "01", "{} trailing",
                "\"\\u+041\"", "\"\\u-001\"", "\"\\uGGGG\"", "\"\\u00\""}) {
            assertThrows(IOException.class, () -> JsonCodec.parse(value), value);
        }
    }
}
