package ai.core.mcp.client;

import ai.core.utils.SystemUtil;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.ProtocolVersions;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @author stephen
 */
public final class McpTransportFactory {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpTransportFactory.class);
    private static final String SDK_DEFAULT_STREAMABLE_ENDPOINT = "/mcp";
    private static final String SDK_DEFAULT_SSE_ENDPOINT = "/sse";

    /**
     * Supported protocol versions for Streamable HTTP transport.
     * {@link ProtocolVersions#MCP_2025_11_25} is excluded because it is marked
     * {@code @Deprecated} in the SDK and is not yet widely supported by MCP servers
     * (e.g., clickhouse-mcp only supports up to 2025-06-18).
     */
    private static final List<String> SUPPORTED_PROTOCOL_VERSIONS = List.of(
            ProtocolVersions.MCP_2024_11_05,
            ProtocolVersions.MCP_2025_03_26,
            ProtocolVersions.MCP_2025_06_18
    );

    public static McpClientTransport createTransport(McpServerConfig config) {
        return switch (config.getTransportType()) {
            case STDIO -> createStdioTransport(config);
            case STREAMABLE_HTTP -> createStreamableHttpTransport(config);
            case SSE -> createSseTransport(config);
            case SANDBOX_HOSTED -> throw new IllegalStateException(
                "SANDBOX_HOSTED transport must be resolved to STREAMABLE_HTTP before creating transport. "
                + "The server module should transform the config with sandbox URL.");
        };
    }

    private static McpClientTransport createStdioTransport(McpServerConfig config) {
        var command = config.getCommand();
        var args = config.getArgs() != null ? new ArrayList<>(config.getArgs()) : new ArrayList<String>();

        if (SystemUtil.detectPlatform().isWindows() && isWindowsScriptCommand(command)) {
            args.addFirst(command);
            args.addFirst("/c");
            command = "cmd.exe";
        }

        var paramsBuilder = ServerParameters.builder(command);

        if (!args.isEmpty()) {
            paramsBuilder.args(args);
        }

        if (config.getEnv() != null && !config.getEnv().isEmpty()) {
            paramsBuilder.env(config.getEnv());
        }

        return new StdioClientTransport(paramsBuilder.build(), McpJsonDefaults.getMapper());
    }

    private static boolean isWindowsScriptCommand(String command) {
        var cmd = command.toLowerCase(Locale.ROOT);
        return cmd.equals("npx") || cmd.equals("npm") || cmd.equals("node")
                || cmd.equals("pnpm") || cmd.equals("yarn") || cmd.equals("bun")
                || cmd.endsWith(".cmd") || cmd.endsWith(".bat");
    }

    private static McpClientTransport createStreamableHttpTransport(McpServerConfig config) {
        String url = config.getUrl();
        String endpoint = config.getEndpoint();

        if ((endpoint == null || endpoint.isBlank()) && url.endsWith(SDK_DEFAULT_STREAMABLE_ENDPOINT)) {
            String baseUrl = url.substring(0, url.length() - SDK_DEFAULT_STREAMABLE_ENDPOINT.length()) + "/";
            endpoint = SDK_DEFAULT_STREAMABLE_ENDPOINT.substring(1); // "mcp" (relative)

            var builder = HttpClientStreamableHttpTransport.builder(baseUrl)
                .connectTimeout(config.getConnectTimeout())
                .endpoint(endpoint)
                .supportedProtocolVersions(SUPPORTED_PROTOCOL_VERSIONS);
            return applyHeadersAndBuild(builder, config);
        }

        var builder = HttpClientStreamableHttpTransport.builder(url)
            .connectTimeout(config.getConnectTimeout())
            .supportedProtocolVersions(SUPPORTED_PROTOCOL_VERSIONS);

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpoint(endpoint);
        }

        return applyHeadersAndBuild(builder, config);
    }

    private static McpClientTransport applyHeadersAndBuild(HttpClientStreamableHttpTransport.Builder builder, McpServerConfig config) {
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            builder.customizeRequest(requestBuilder -> {
                for (var entry : config.getHeaders().entrySet()) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }
            });
        }
        return builder.build();
    }

    private static McpClientTransport createSseTransport(McpServerConfig config) {
        String url = config.getUrl();
        String endpoint = config.getEndpoint();

        if ((endpoint == null || endpoint.isBlank()) && url.endsWith(SDK_DEFAULT_SSE_ENDPOINT)) {
            String baseUrl = url.substring(0, url.length() - SDK_DEFAULT_SSE_ENDPOINT.length()) + "/";
            endpoint = SDK_DEFAULT_SSE_ENDPOINT.substring(1); // "sse" (relative)

            var builder = HttpClientSseClientTransport.builder(baseUrl)
                .connectTimeout(config.getConnectTimeout())
                .sseEndpoint(endpoint);
            return applySseHeadersAndBuild(builder, config);
        }

        var builder = HttpClientSseClientTransport.builder(url)
            .connectTimeout(config.getConnectTimeout());

        if (endpoint != null && !endpoint.isBlank()) {
            builder.sseEndpoint(endpoint);
        }

        return applySseHeadersAndBuild(builder, config);
    }

    private static McpClientTransport applySseHeadersAndBuild(HttpClientSseClientTransport.Builder builder, McpServerConfig config) {
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            builder.customizeRequest(requestBuilder -> {
                for (var entry : config.getHeaders().entrySet()) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }
            });
        }
        return builder.build();
    }

    public static McpSyncClient createClient(McpClientTransport transport, McpServerConfig config) {
        var syncClient = McpClient.sync(transport)
            .requestTimeout(config.getRequestTimeout())
            .initializationTimeout(config.getInitializationTimeout())
            .build();
        syncClient.initialize();
        LOGGER.debug("MCP client initialized: name={}, transport={}, requestTimeout={}s",
            config.getName(), config.getTransportType(), config.getRequestTimeout().toSeconds());
        return syncClient;
    }

    private McpTransportFactory() {
    }
}
