package ai.core.mcp.client;

import java.util.Map;

/**
 * @author stephen
 */
public record McpClientServerConfig(
    String url,
    String endpoint,
    String name,
    String description,
    Map<String, String> headers
) {
    public McpClientServerConfig(String url, String endpoint, String name, String description) {
        this(url, endpoint, name, description, Map.of());
    }
}
