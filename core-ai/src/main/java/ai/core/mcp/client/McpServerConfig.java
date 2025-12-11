package ai.core.mcp.client;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class McpServerConfig {
    /**
     * Create a builder for STDIO transport configuration.
     */
    public static StdioBuilder stdio(String command) {
        return new StdioBuilder(command);
    }

    /**
     * Create a builder for HTTP transport configuration.
     */
    public static HttpBuilder http(String url) {
        return new HttpBuilder(url);
    }

    /**
     * Parse configuration from a map (compatible with Claude Desktop format).
     */
    public static McpServerConfig fromMap(String serverName, Map<String, Object> config) {
        if (config.containsKey("command")) {
            return parseStdioConfig(serverName, config);
        } else if (config.containsKey("url")) {
            return parseHttpConfig(serverName, config);
        }
        throw new IllegalArgumentException("Invalid config: must have 'command' (STDIO) or 'url' (HTTP)");
    }

    /**
     * Parse multiple server configurations from Claude Desktop format.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, McpServerConfig> parseServers(Map<String, Object> mcpServersConfig) {
        var result = new HashMap<String, McpServerConfig>();
        for (var entry : mcpServersConfig.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> serverConfig) {
                result.put(entry.getKey(), fromMap(entry.getKey(), (Map<String, Object>) serverConfig));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private static McpServerConfig parseStdioConfig(String serverName, Map<String, Object> config) {
        var builder = stdio((String) config.get("command")).name(serverName);

        if (config.get("args") instanceof List<?> argsList) {
            builder.args(argsList.stream().map(Object::toString).toList());
        }

        if (config.get("env") instanceof Map<?, ?> envMap) {
            builder.env((Map<String, String>) envMap);
        }

        parseCommonConfig(builder, config);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static McpServerConfig parseHttpConfig(String serverName, Map<String, Object> config) {
        var builder = http((String) config.get("url")).name(serverName);

        if (config.containsKey("endpoint")) {
            builder.endpoint((String) config.get("endpoint"));
        }

        if (config.containsKey("transport")) {
            String transport = (String) config.get("transport");
            if ("sse".equalsIgnoreCase(transport)) {
                builder.transportType(TransportType.SSE);
            }
        }

        if (config.get("headers") instanceof Map<?, ?> headersMap) {
            builder.headers((Map<String, String>) headersMap);
        }

        parseCommonConfig(builder, config);
        return builder.build();
    }

    /**
     * Parse common configuration options (heartbeat, timeout, reconnect) from config map.
     * Supports both StdioBuilder and HttpBuilder through the CommonConfigBuilder interface.
     */
    private static void parseCommonConfig(CommonConfigBuilder<?> builder, Map<String, Object> config) {
        // Heartbeat configuration: "heartbeat": 30 means enable heartbeat with 30s interval
        if (config.containsKey("heartbeat")) {
            Object heartbeatValue = config.get("heartbeat");
            if (heartbeatValue instanceof Number num) {
                builder.enableHeartbeat(true);
                builder.heartbeatInterval(Duration.ofSeconds(num.longValue()));
            } else if (heartbeatValue instanceof Boolean bool) {
                builder.enableHeartbeat(bool);
            }
        }

        // Heartbeat timeout: "heartbeatTimeout": 10
        if (config.containsKey("heartbeatTimeout")) {
            Object value = config.get("heartbeatTimeout");
            if (value instanceof Number num) {
                builder.heartbeatTimeout(Duration.ofSeconds(num.longValue()));
            }
        }

        // Connect timeout: "connectTimeout": 10
        if (config.containsKey("connectTimeout")) {
            Object value = config.get("connectTimeout");
            if (value instanceof Number num) {
                builder.connectTimeout(Duration.ofSeconds(num.longValue()));
            }
        }

        // Request timeout: "requestTimeout": 60
        if (config.containsKey("requestTimeout")) {
            Object value = config.get("requestTimeout");
            if (value instanceof Number num) {
                builder.requestTimeout(Duration.ofSeconds(num.longValue()));
            }
        }

        // Auto reconnect: "autoReconnect": true/false
        if (config.containsKey("autoReconnect")) {
            Object value = config.get("autoReconnect");
            if (value instanceof Boolean bool) {
                builder.autoReconnect(bool);
            }
        }

        // Max reconnect attempts: "maxReconnectAttempts": 3
        if (config.containsKey("maxReconnectAttempts")) {
            Object value = config.get("maxReconnectAttempts");
            if (value instanceof Number num) {
                builder.maxReconnectAttempts(num.intValue());
            }
        }

        // Reconnect interval: "reconnectInterval": 5
        if (config.containsKey("reconnectInterval")) {
            Object value = config.get("reconnectInterval");
            if (value instanceof Number num) {
                builder.reconnectInterval(Duration.ofSeconds(num.longValue()));
            }
        }
    }

    private String name;
    private TransportType transportType;

    // STDIO transport fields
    private String command;
    private List<String> args;
    private Map<String, String> env;

    // HTTP transport fields
    private String url;
    private String endpoint;
    private Map<String, String> headers;

    // Timeout configuration
    private Duration connectTimeout = Duration.ofSeconds(10);
    private Duration requestTimeout = Duration.ofSeconds(60);

    // Reconnect configuration
    private boolean autoReconnect = true;
    private int maxReconnectAttempts = 3;
    private Duration reconnectInterval = Duration.ofSeconds(5);
    private Duration reconnectBackoffMax = Duration.ofSeconds(60);

    // Heartbeat configuration
    private boolean enableHeartbeat = true;
    private Duration heartbeatInterval = Duration.ofSeconds(30);
    private Duration heartbeatTimeout = Duration.ofSeconds(10);

    public String getName() {
        return name;
    }

    public TransportType getTransportType() {
        return transportType;
    }

    public String getCommand() {
        return command;
    }

    public List<String> getArgs() {
        return args;
    }

    public Map<String, String> getEnv() {
        return env;
    }

    public String getUrl() {
        return url;
    }

    public String getEndpoint() {
        return endpoint;
    }

    public Map<String, String> getHeaders() {
        return headers;
    }

    public Duration getConnectTimeout() {
        return connectTimeout;
    }

    public Duration getRequestTimeout() {
        return requestTimeout;
    }

    public boolean isAutoReconnect() {
        return autoReconnect;
    }

    public int getMaxReconnectAttempts() {
        return maxReconnectAttempts;
    }

    public Duration getReconnectInterval() {
        return reconnectInterval;
    }

    public Duration getReconnectBackoffMax() {
        return reconnectBackoffMax;
    }

    public boolean isEnableHeartbeat() {
        return enableHeartbeat;
    }

    public Duration getHeartbeatInterval() {
        return heartbeatInterval;
    }

    public Duration getHeartbeatTimeout() {
        return heartbeatTimeout;
    }

    public boolean isStdio() {
        return transportType == TransportType.STDIO;
    }

    public boolean isHttp() {
        return transportType == TransportType.STREAMABLE_HTTP || transportType == TransportType.SSE;
    }

    public enum TransportType {
        STDIO,
        STREAMABLE_HTTP,
        SSE
    }

    private interface CommonConfigBuilder<T> {
        void enableHeartbeat(boolean enable);
        void heartbeatInterval(Duration interval);
        void heartbeatTimeout(Duration timeout);
        void connectTimeout(Duration timeout);
        void requestTimeout(Duration timeout);
        void autoReconnect(boolean autoReconnect);
        void maxReconnectAttempts(int maxAttempts);
        void reconnectInterval(Duration interval);
    }

    public static final class StdioBuilder implements CommonConfigBuilder<StdioBuilder> {
        private final McpServerConfig config = new McpServerConfig();

        private StdioBuilder(String command) {
            config.transportType = TransportType.STDIO;
            config.command = command;
            config.args = new ArrayList<>();
            config.env = new HashMap<>();
        }

        public StdioBuilder name(String name) {
            config.name = name;
            return this;
        }

        public StdioBuilder args(String... args) {
            config.args = new ArrayList<>(List.of(args));
            return this;
        }

        public StdioBuilder args(List<String> args) {
            config.args = new ArrayList<>(args);
            return this;
        }

        public StdioBuilder arg(String arg) {
            config.args.add(arg);
            return this;
        }

        public StdioBuilder env(Map<String, String> env) {
            config.env = new HashMap<>(env);
            return this;
        }

        public StdioBuilder envVar(String key, String value) {
            config.env.put(key, value);
            return this;
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

        public StdioBuilder reconnectBackoffMax(Duration max) {
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
            if (config.command == null || config.command.isBlank()) {
                throw new IllegalArgumentException("command is required for STDIO transport");
            }
            return config;
        }
    }

    public static final class HttpBuilder implements CommonConfigBuilder<HttpBuilder> {
        private final McpServerConfig config = new McpServerConfig();

        private HttpBuilder(String url) {
            config.transportType = TransportType.STREAMABLE_HTTP;
            config.url = url;
            config.headers = new HashMap<>();
        }

        public HttpBuilder name(String name) {
            config.name = name;
            return this;
        }

        public HttpBuilder endpoint(String endpoint) {
            config.endpoint = endpoint;
            return this;
        }

        public HttpBuilder transportType(TransportType transportType) {
            if (transportType == TransportType.STDIO) {
                throw new IllegalArgumentException("Use stdio() builder for STDIO transport");
            }
            config.transportType = transportType;
            return this;
        }

        public HttpBuilder headers(Map<String, String> headers) {
            config.headers = new HashMap<>(headers);
            return this;
        }

        public HttpBuilder header(String key, String value) {
            config.headers.put(key, value);
            return this;
        }

        public HttpBuilder bearerToken(String token) {
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

        public HttpBuilder reconnectBackoffMax(Duration max) {
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
}
