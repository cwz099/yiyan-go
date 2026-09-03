package com.yiyan.go.ai;

import com.yiyan.go.diagnostics.AppLogs;
import java.net.URI;
import java.time.Duration;

public record DeepSeekConfig(String apiKey, URI endpoint, String model, Duration timeout) {
    public static final String DEFAULT_ENDPOINT = "https://api.deepseek.com/chat/completions";
    public static final String DEFAULT_MODEL = "deepseek-v4-flash";

    public DeepSeekConfig {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("需要填写 API Key");
        }
        if (endpoint == null || !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new IllegalArgumentException("API 地址必须使用 HTTPS");
        }
        if (endpoint.getHost() == null) throw new IllegalArgumentException("API 地址需要有效的主机名");
        if (endpoint.getRawUserInfo() != null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null) {
            throw new IllegalArgumentException("API 地址不能包含账号、查询参数或片段；请通过 API Key 字段认证");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("模型名称不能为空");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("超时时间必须大于 0");
        }
        AppLogs.registerSecret(apiKey);
    }

    public static DeepSeekConfig of(String apiKey, String endpoint, String model) {
        return new DeepSeekConfig(apiKey.trim(), URI.create(endpoint.trim()), model.trim(), Duration.ofSeconds(45));
    }

    @Override
    public String toString() {
        return "DeepSeekConfig[apiKey=<redacted>, host=" + AppLogs.safeHost(endpoint)
                + ", model=" + AppLogs.redact(model) + ", timeout=" + timeout + "]";
    }
}
