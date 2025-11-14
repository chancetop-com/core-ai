package ai.core.mcp.client;

import java.util.Map;

/**
 * Configuration for MCP Client Server connection.
 * 
 * @param url MCP server URL
 * @param endpoint MCP endpoint path
 * @param name Server name
 * @param description Server description
 * @param headers Custom HTTP headers to include in all requests
 * 
 * @author stephen
 */
public record McpClientServerConfig(
    String url,
    String endpoint,
    String name,
    String description,
    Map<String, String> headers
) {
    /**
     * Create config with URL only (minimal configuration).
     */
    public McpClientServerConfig(String url) {
        this(url, null, null, null, Map.of());
    }
    
    /**
     * Create config with URL and custom headers.
     */
    public McpClientServerConfig(String url, Map<String, String> headers) {
        this(url, null, null, null, headers);
    }
    
    /**
     * Create config without custom headers.
     */
    public McpClientServerConfig(String url, String endpoint, String name, String description) {
        this(url, endpoint, name, description, Map.of());
    }
    
    /**
     * Builder for creating McpClientServerConfig with custom headers.
     */
    public static Builder builder(String url) {
        return new Builder(url);
    }
    
    public static final class Builder {
        private final String url;
        private String endpoint;
        private String name;
        private String description;
        private final Map<String, String> headers = new java.util.HashMap<>();
        
        private Builder(String url) {
            this.url = url;
        }
        
        public Builder endpoint(String endpoint) {
            this.endpoint = endpoint;
            return this;
        }
        
        public Builder name(String name) {
            this.name = name;
            return this;
        }
        
        public Builder description(String description) {
            this.description = description;
            return this;
        }
        
        /**
         * Add a custom header.
         */
        public Builder header(String key, String value) {
            this.headers.put(key, value);
            return this;
        }
        
        /**
         * Add multiple custom headers.
         */
        public Builder headers(Map<String, String> headers) {
            this.headers.putAll(headers);
            return this;
        }
        
        /**
         * Add Authorization header.
         */
        public Builder authorization(String token) {
            return header("Authorization", token);
        }
        
        /**
         * Add Bearer token authorization.
         */
        public Builder bearerToken(String token) {
            return header("Authorization", "Bearer " + token);
        }
        
        /**
         * Add MCP Session ID header.
         */
        public Builder sessionId(String sessionId) {
            return header("Mcp-Session-Id", sessionId);
        }
        
        public McpClientServerConfig build() {
            return new McpClientServerConfig(url, endpoint, name, description, Map.copyOf(headers));
        }
    }
}
