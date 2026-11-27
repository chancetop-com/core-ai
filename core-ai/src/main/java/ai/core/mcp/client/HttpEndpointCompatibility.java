package ai.core.mcp.client;

import java.net.URI;

/**
 * Endpoint-specific quirks applied before an HTTP transport starts.
 *
 * @author stephen
 */
final class HttpEndpointCompatibility {
    static void applyDefaults(McpServerConfig config) {
        if (isMetaAdsEndpoint(config)
            && config.headers.keySet().stream().noneMatch("Accept"::equalsIgnoreCase)) {
            // Meta returns HTTP 200 + an empty text/event-stream body for the
            // initialized notification. The Java SDK waits for an SSE event that
            // never arrives; asking for JSON makes the same response complete.
            config.headers.put("Accept", "application/json");
        }
    }

    private static boolean isMetaAdsEndpoint(McpServerConfig config) {
        try {
            if (config.transportType != TransportType.STREAMABLE_HTTP) {
                return false;
            }
            var baseUri = URI.create(config.url);
            var endpointUri = config.endpoint == null || config.endpoint.isBlank()
                ? baseUri
                : baseUri.resolve(URI.create(config.endpoint));
            if (!"https".equalsIgnoreCase(endpointUri.getScheme())
                || !"mcp.facebook.com".equalsIgnoreCase(endpointUri.getHost())
                || endpointUri.getPort() != -1 && endpointUri.getPort() != 443) {
                return false;
            }
            String path = endpointUri.normalize().getPath();
            if (path == null) {
                return false;
            }
            path = path.strip();
            while (path.length() > 1 && path.endsWith("/")) {
                path = path.substring(0, path.length() - 1);
            }
            return "/ads".equals(path) || "ads".equals(path);
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    private HttpEndpointCompatibility() {
    }
}
