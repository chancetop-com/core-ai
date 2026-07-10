package ai.core.mcp.client;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * @author stephen
 */
public final class HttpServerConfigBuilder implements McpServerConfig.CommonConfigBuilder<HttpServerConfigBuilder> {

    // strip BOM, zero-width chars and surrounding whitespace/quotes that may
    // sneak in via copy-paste or DB import and break URI parsing
    private static String sanitizeUrl(String url) {
        if (url == null) return null;
        var cleaned = url.replaceAll("[\\uFEFF\\u200B\\u200C\\u200D\\u2060]", "").strip();
        if (cleaned.length() >= 2) {
            char first = cleaned.charAt(0);
            char last = cleaned.charAt(cleaned.length() - 1);
            if ((first == '"' || first == '\'') && first == last) {
                cleaned = cleaned.substring(1, cleaned.length() - 1).strip();
            }
        }
        return cleaned;
    }

    private final McpServerConfig config = new McpServerConfig();

    HttpServerConfigBuilder(String url) {
        config.transportType = TransportType.STREAMABLE_HTTP;
        config.url = sanitizeUrl(url);
        config.headers = new HashMap<>();
    }

    public HttpServerConfigBuilder name(String name) {
        config.name = name;
        return this;
    }

    public HttpServerConfigBuilder endpoint(String endpoint) {
        config.endpoint = endpoint;
        return this;
    }

    public HttpServerConfigBuilder transportType(TransportType transportType) {
        if (transportType == TransportType.STDIO) {
            throw new IllegalArgumentException("Use stdio() builder for STDIO transport");
        }
        config.transportType = transportType;
        return this;
    }

    public HttpServerConfigBuilder headers(Map<String, String> headers) {
        config.headers = new HashMap<>(headers);
        return this;
    }

    public HttpServerConfigBuilder header(String key, String value) {
        config.headers.put(key, value);
        return this;
    }

    public HttpServerConfigBuilder bearerToken(String token) {
        return header("Authorization", "Bearer " + token);
    }

    @Override
    public void connectTimeout(Duration timeout) {
        config.connectTimeout = timeout;
    }

    @Override
    public void requestTimeout(Duration timeout) {
        config.requestTimeout = timeout;
    }

    @Override
    public void initializationTimeout(Duration timeout) {
        config.initializationTimeout = timeout;
    }

    @Override
    public void autoReconnect(boolean autoReconnect) {
        config.autoReconnect = autoReconnect;
    }

    @Override
    public void maxReconnectAttempts(int maxAttempts) {
        config.maxReconnectAttempts = maxAttempts;
    }

    @Override
    public void reconnectInterval(Duration interval) {
        config.reconnectInterval = interval;
    }

    public HttpServerConfigBuilder reconnectBackoffMax(Duration max) {
        config.reconnectBackoffMax = max;
        return this;
    }

    @Override
    public void enableHeartbeat(boolean enable) {
        config.enableHeartbeat = enable;
    }

    @Override
    public void heartbeatInterval(Duration interval) {
        config.heartbeatInterval = interval;
    }

    @Override
    public void heartbeatTimeout(Duration timeout) {
        config.heartbeatTimeout = timeout;
    }

    public McpServerConfig build() {
        if (config.url == null || config.url.isBlank()) {
            throw new IllegalArgumentException("url is required for HTTP transport");
        }
        return config;
    }
}
