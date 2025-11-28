package ai.core.mcp.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Configuration for MCP Server connection.
 * Supports both STDIO (command-based) and HTTP (URL-based) transports.
 * <p>
 * Example JSON configurations:
 * <pre>
 * // STDIO transport (like Claude Desktop)
 * {
 *   "mcpServers": {
 *     "chrome-devtools": {
 *       "command": "npx",
 *       "args": ["-y", "chrome-devtools-mcp@latest"]
 *     }
 *   }
 * }
 *
 * // HTTP transport
 * {
 *   "mcpServers": {
 *     "my-server": {
 *       "url": "https://mcp-server.example.com",
 *       "transport": "streamable-http"
 *     }
 *   }
 * }
 * </pre>
 *
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

        return builder.build();
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

    public static final class StdioBuilder {
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

        public McpServerConfig build() {
            if (config.command == null || config.command.isBlank()) {
                throw new IllegalArgumentException("command is required for STDIO transport");
            }
            return config;
        }
    }

    public static final class HttpBuilder {
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

        public McpServerConfig build() {
            if (config.url == null || config.url.isBlank()) {
                throw new IllegalArgumentException("url is required for HTTP transport");
            }
            return config;
        }
    }
}
