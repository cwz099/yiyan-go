package com.yiyan.go.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

public final class DeepSeekOpponentTest {
    @Test
    void extractsPlainAndEscapedJsonStrings() throws Exception {
        String response = "{\"choices\":[{\"message\":{\"content\":\"{\\\"move\\\":\\\"Q10\\\",\\\"motivation\\\":\\\"向中央发展\\n并保持联络\\u3002\\\"}\"}}]}";
        String content = DeepSeekOpponent.extractJsonString(response, "content");
        assertEquals("{\"move\":\"Q10\",\"motivation\":\"向中央发展\n并保持联络。\"}", content,
                "应解码外层 content 字符串");
        assertEquals("Q10", DeepSeekOpponent.extractJsonString(content, "move"), "应读取落子坐标");
        assertEquals("向中央发展\n并保持联络。", DeepSeekOpponent.extractJsonString(content, "motivation"),
                "应读取并解码说明文本");
        assertNull(DeepSeekOpponent.extractJsonString("{}", "content"), "缺失字段应返回 null");
    }
}
