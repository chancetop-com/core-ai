package ai.core.mcp.client;

import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class McpServerConfig {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpServerConfig.class);
    public static StdioBuilder stdio(String command) {
        return new StdioBuilder(command);
    }
    public static HttpServerConfigBuilder http(String url) {
        return new HttpServerConfigBuilder(url);
    }
    public static McpServerConfig fromMap(String serverName, Map<String, Object> config) {
        String transport = (String) config.get("transport");
        if ("sandbox_hosted".equalsIgnoreCase(transport) && config.containsKey("command")) {
            return parseSandboxHostedConfig(serverName, config);
        }
        if (config.containsKey("command")) {
            return parseStdioConfig(serverName, config);
        } else if (config.containsKey("url")) {
            return parseHttpConfig(serverName, config);
        }
        throw new IllegalArgumentException("Invalid config: must have 'command' (STDIO) or 'url' (HTTP)");
    }

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

    private static McpServerConfig parseStdioConfig(String serverName, Map<String, Object> config) {
        var builder = stdio((String) config.get("command")).name(serverName);

        if (config.get("args") instanceof List<?> argsList) {
            builder.args(argsList.stream().map(Object::toString).toList());
        }

        var env = parseEnv(config.get("env"));
        if (!env.isEmpty()) builder.env(env);

        parseCommonConfig(builder, config);
        return builder.build();
    }

    private static McpServerConfig parseSandboxHostedConfig(String serverName, Map<String, Object> config) {
        var builder = stdio((String) config.get("command")).name(serverName);
        builder.transportType(TransportType.SANDBOX_HOSTED);

        if (config.get("args") instanceof List<?> argsList) {
            builder.args(argsList.stream().map(Object::toString).toList());
        }

        var env = parseEnv(config.get("env"));
        if (!env.isEmpty()) builder.env(env);

        parseCommonConfig(builder, config);
        return builder.build();
    }

    @SuppressWarnings("unchecked")
    private static McpServerConfig parseHttpConfig(String serverName, Map<String, Object> config) {
        var builder = http((String) config.get("url")).name(serverName);

        String endpoint = (String) config.get("endpoint");
        if (endpoint != null) {
            builder.endpoint(endpoint);
        }

        String transport = (String) config.get("transport");
        if (transport != null && "sse".equalsIgnoreCase(transport)) {
            builder.transportType(TransportType.SSE);
        }

        Object headersObj = config.get("headers");
        if (headersObj instanceof String headersStr && !headersStr.isBlank()) {
            builder.headers(parseHeadersJson(serverName, headersStr));
        } else if (headersObj instanceof Map<?, ?> headersMap) {
            builder.headers((Map<String, String>) headersMap);
        }

        parseCommonConfig(builder, config);
        return builder.build();
    }
    private static void parseCommonConfig(CommonConfigBuilder<?> builder, Map<String, Object> config) {
        Object heartbeatValue = config.get("heartbeat");
        if (heartbeatValue instanceof Number num) {
            builder.enableHeartbeat(true);
            builder.heartbeatInterval(Duration.ofSeconds(num.longValue()));
        } else if (heartbeatValue instanceof Boolean bool) {
            builder.enableHeartbeat(bool);
        }
        parseDuration(config, "heartbeatTimeout", builder::heartbeatTimeout);
        parseDuration(config, "connectTimeout", builder::connectTimeout);
        parseDuration(config, "requestTimeout", builder::requestTimeout);
        parseDuration(config, "initializationTimeout", builder::initializationTimeout);
        if (config.get("autoReconnect") instanceof Boolean bool) {
            builder.autoReconnect(bool);
        }
        if (config.get("maxReconnectAttempts") instanceof Number num) {
            builder.maxReconnectAttempts(num.intValue());
        }
        parseDuration(config, "reconnectInterval", builder::reconnectInterval);
    }
    private static void parseDuration(Map<String, Object> config, String key, java.util.function.Consumer<Duration> setter) {
        if (config.get(key) instanceof Number num) {
            setter.accept(Duration.ofSeconds(num.longValue()));
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, String> parseHeadersJson(String serverName, String headersStr) {
        try {
            Map<String, Object> parsed = JsonUtil.fromJson(Map.class, headersStr);
            var result = new HashMap<String, String>();
            for (var entry : parsed.entrySet()) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
            return result;
        } catch (Exception e) {
            LOGGER.warn("failed to parse headers json for server '{}', raw: {}", serverName, headersStr, e);
            return Map.of();
        }
    }

    // Accepts env as either a Map<String,String> or a JSON object string (the form it takes
    // when stored in a Map<String,String> config from the database).
    @SuppressWarnings("unchecked")
    public static Map<String, String> parseEnv(Object envObj) {
        if (envObj instanceof Map<?, ?> envMap) {
            var result = new HashMap<String, String>();
            for (var entry : envMap.entrySet()) {
                result.put(String.valueOf(entry.getKey()), String.valueOf(entry.getValue()));
            }
            return result;
        }
        if (envObj instanceof String envStr && !envStr.isBlank()) {
            try {
                Map<String, Object> parsed = JsonUtil.fromJson(Map.class, envStr);
                var result = new HashMap<String, String>();
                for (var entry : parsed.entrySet()) {
                    result.put(entry.getKey(), String.valueOf(entry.getValue()));
                }
                return result;
            } catch (Exception e) {
                LOGGER.warn("failed to parse env as JSON: {}", envStr, e);
            }
        }
        return Map.of();
    }
    String name;
    TransportType transportType;

    // STDIO transport fields
    String command;
    List<String> args;
    Map<String, String> env;

    // HTTP transport fields
    String url;
    String endpoint;
    Map<String, String> headers;

    // Timeout configuration
    Duration connectTimeout = Duration.ofSeconds(10);
    Duration requestTimeout = Duration.ofSeconds(60);
    Duration initializationTimeout = Duration.ofSeconds(60);

    // Reconnect configuration
    boolean autoReconnect = true;
    int maxReconnectAttempts = 3;
    Duration reconnectInterval = Duration.ofSeconds(5);
    Duration reconnectBackoffMax = Duration.ofSeconds(60);

    // Heartbeat configuration
    boolean enableHeartbeat = true;
    Duration heartbeatInterval = Duration.ofSeconds(30);
    Duration heartbeatTimeout = Duration.ofSeconds(10);
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
    public Duration getInitializationTimeout() {
        return initializationTimeout;
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
    public boolean isSandboxHosted() {
        return transportType == TransportType.SANDBOX_HOSTED;
    }
    interface CommonConfigBuilder<T> {
        void enableHeartbeat(boolean enable);
        void heartbeatInterval(Duration interval);
        void heartbeatTimeout(Duration timeout);
        void connectTimeout(Duration timeout);
        void requestTimeout(Duration timeout);
        void initializationTimeout(Duration timeout);
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

        public StdioBuilder transportType(TransportType transportType) {
            config.transportType = transportType;
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
}
