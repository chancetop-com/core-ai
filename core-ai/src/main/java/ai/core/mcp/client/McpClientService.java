package ai.core.mcp.client;

import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import ai.core.utils.SystemUtil;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;

import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeoutException;

/**
 * @author stephen
 */
public class McpClientService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpClientService.class);
    private static final String SDK_DEFAULT_STREAMABLE_ENDPOINT = "/mcp";
    private static final String SDK_DEFAULT_SSE_ENDPOINT = "/sse";

    private final McpSyncClient client;
    private final McpClientTransport transport;
    private final McpServerConfig config;
    private final String serverName;
    private Process stdioProcess;

    public McpClientService(McpServerConfig config) {
        this.config = config;
        this.serverName = config.getName();
        McpClientTransport createdTransport = null;
        McpSyncClient createdClient = null;
        try {
            createdTransport = createTransport(config);
            // extract subprocess handle right after transport creation so failure cleanup can kill it
            if (createdTransport instanceof StdioClientTransport stdioTransport) {
                extractProcessFromTransport(stdioTransport);
            }
            createdClient = createClient(createdTransport, config);
        } catch (RuntimeException e) {
            // initialize() failure (auth/network/protocol) must not leak HttpClient, SSE stream or stdio subprocess
            closeQuietly(createdClient, createdTransport);
            throw e;
        }
        this.transport = createdTransport;
        this.client = createdClient;
    }

    private void closeQuietly(McpSyncClient partialClient, McpClientTransport partialTransport) {
        if (partialClient != null) {
            try {
                partialClient.closeGracefully();
            } catch (Exception suppressed) {
                LOGGER.warn("Failed to close partially-initialized MCP client: {}", serverName, suppressed);
            }
        }
        if (partialTransport != null) {
            try {
                partialTransport.closeGracefully().block(Duration.ofSeconds(2));
            } catch (Exception suppressed) {
                LOGGER.warn("Failed to close partially-initialized MCP transport: {}", serverName, suppressed);
            }
        }
        if (stdioProcess != null && stdioProcess.isAlive()) {
            forceDestroyProcessTree(stdioProcess);
            stdioProcess = null;
        }
    }

    public List<McpSchema.Tool> listTools(List<String> namespaces) {
        var tools = listToolsRaw();

        if (namespaces == null || namespaces.isEmpty()) {
            return tools;
        }

        return tools.stream()
            .filter(tool -> namespaces.stream()
                .anyMatch(ns -> tool.name().startsWith(ns + "_") || tool.name().startsWith(ns + "/")))
            .toList();
    }

    public List<McpSchema.Tool> listTools() {
        return listTools(null);
    }

    public List<McpSchema.Tool> listToolsRaw() {
        var result = client.listTools();
        var tools = result.tools();
        return tools != null ? tools : List.of();
    }

    public ToolCallResult callToolWithResult(String name, String text) {
        return callToolWithResult(name, JsonUtil.toMap(text));
    }

    public ToolCallResult callToolWithResult(String name, Map<String, Object> arguments) {
        var request = new McpSchema.CallToolRequest(name, arguments);
        var result = client.callTool(request);

        if (result.isError() != null && result.isError()) {
            return ToolCallResult.failed(extractErrorMessage(result));
        }

        return extractToolCallResult(result);
    }

    public McpSyncClient getMcpClient() {
        return client;
    }

    public boolean isInitialized() {
        return client.isInitialized();
    }

    public McpSchema.ServerCapabilities getServerCapabilities() {
        return client.getServerCapabilities();
    }

    public String getServerName() {
        return serverName;
    }

    public McpServerConfig getConfig() {
        return config;
    }

    public boolean ping(Duration timeout) {
        try {
            var pingResult = reactor.core.publisher.Mono
                .fromCallable(this::doPing)
                .timeout(timeout)
                .onErrorReturn(TimeoutException.class, false)
                .block();
            return Boolean.TRUE.equals(pingResult);
        } catch (Exception e) {
            LOGGER.warn("Ping failed for server {}: {}", serverName, e.getMessage());
            return false;
        }
    }

    public boolean ping() {
        return ping(config.getHeartbeatTimeout());
    }

    private boolean doPing() {
        try {
            client.ping();
            return true;
        } catch (Exception e) {
            LOGGER.debug("Ping not supported, trying listTools as fallback: {}", serverName);
            client.listTools();
            return true;
        }
    }

    @Override
    public void close() {
        LOGGER.debug("Closing MCP client: {}", serverName);

        // Kill the subprocess tree first — on Windows with Ctrl+C, the cmd.exe
        // wrapper may already be dead by the time shutdown hooks run, so we must
        // try to clean up before that window closes.
        if (stdioProcess != null) {
            forceDestroyProcessTree(stdioProcess);
        } else {
            LOGGER.debug("No subprocess to destroy for: {}", serverName);
        }

        if (client != null) {
            try {
                client.closeGracefully();
            } catch (Exception e) {
                LOGGER.warn("Error closing MCP client gracefully: {}", serverName, e);
            }
        }
        if (transport != null) {
            try {
                transport.closeGracefully().block(Duration.ofSeconds(5));
            } catch (Exception e) {
                LOGGER.warn("Error closing MCP transport: {}", serverName, e);
            }
        }

        LOGGER.debug("MCP client closed: {}", serverName);
    }

    private void forceDestroyProcessTree(Process process) {
        long pid = process.pid();

        if (!process.isAlive()) {
            LOGGER.debug("Process already terminated for: {}, attempting fallback cleanup", serverName);
            // On Windows, when the parent process (cmd.exe wrapper) is killed by
            // Ctrl+C before we get here, its descendants (npx, node) may still be
            // running as orphans. process.descendants() won't work on a dead parent,
            // so we find them by parent PID and kill them explicitly.
            if (SystemUtil.detectPlatform().isWindows()) {
                killOrphanedChildrenOnWindows(pid);
            }
            return;
        }

        LOGGER.debug("Force destroying MCP subprocess tree: server={}, pid={}", serverName, pid);

        process.descendants().forEach(ph -> {
            LOGGER.debug("Destroying descendant process: pid={}", ph.pid());
            ph.destroyForcibly();
        });

        process.destroyForcibly();

        try {
            boolean terminated = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!terminated) {
                LOGGER.warn("Process did not terminate within timeout: server={}, pid={}", serverName, pid);
                if (SystemUtil.detectPlatform().isWindows()) {
                    killProcessTreeOnWindows(pid);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.warn("Interrupted while waiting for process termination: {}", serverName);
        }
    }

    private void killProcessTreeOnWindows(long pid) {
        try {
            LOGGER.debug("Using taskkill to forcefully terminate process tree: pid={}", pid);
            var processBuilder = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
            processBuilder.redirectErrorStream(true);
            var killProcess = processBuilder.start();
            killProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Failed to execute taskkill: {}", e.getMessage());
        }
    }

    private void killOrphanedChildrenOnWindows(long parentPid) {
        try {
            LOGGER.debug("Searching for orphaned children of dead parent pid={}", parentPid);
            String psCommand = String.format(
                "Get-CimInstance Win32_Process -Filter \"ParentProcessId=%d\" | ForEach-Object { Stop-Process -Id $_.ProcessId -Force }",
                parentPid);
            var pb = new ProcessBuilder("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", psCommand);
            pb.redirectErrorStream(true);
            var psProcess = pb.start();
            psProcess.waitFor(10, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Failed to kill orphaned children for parent pid={}: {}", parentPid, e.getMessage());
        }
    }

    private McpSyncClient createClient(McpClientTransport transport, McpServerConfig config) {
        var syncClient = McpClient.sync(transport)
            .requestTimeout(config.getRequestTimeout())
            .initializationTimeout(config.getInitializationTimeout())
            .build();
        syncClient.initialize();
        LOGGER.debug("MCP client initialized: name={}, transport={}, requestTimeout={}s",
            config.getName(), config.getTransportType(), config.getRequestTimeout().toSeconds());
        return syncClient;
    }

    private void extractProcessFromTransport(StdioClientTransport transport) {
        // Try to find Process field by scanning all declared fields
        for (Field field : StdioClientTransport.class.getDeclaredFields()) {
            if (Process.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    this.stdioProcess = (Process) field.get(transport);
                    if (this.stdioProcess != null) {
                        LOGGER.debug("Extracted process PID {} from StdioClientTransport field '{}' for cleanup",
                            stdioProcess.pid(), field.getName());
                        return;
                    }
                } catch (IllegalAccessException e) {
                    LOGGER.warn("Failed to access process field '{}': {}", field.getName(), e.getMessage());
                }
            }
        }
        LOGGER.warn("No Process field found in StdioClientTransport, subprocess cleanup may not work properly");
    }

    private McpClientTransport createTransport(McpServerConfig config) {
        return switch (config.getTransportType()) {
            case STDIO -> createStdioTransport(config);
            case STREAMABLE_HTTP -> createStreamableHttpTransport(config);
            case SSE -> createSseTransport(config);
        };
    }

    private McpClientTransport createStdioTransport(McpServerConfig config) {
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

    private boolean isWindowsScriptCommand(String command) {
        var cmd = command.toLowerCase(Locale.ROOT);
        return cmd.equals("npx") || cmd.equals("npm") || cmd.equals("node")
                || cmd.equals("pnpm") || cmd.equals("yarn") || cmd.equals("bun")
                || cmd.endsWith(".cmd") || cmd.endsWith(".bat");
    }

    private McpClientTransport createStreamableHttpTransport(McpServerConfig config) {
        String url = config.getUrl();
        String endpoint = config.getEndpoint();

        if ((endpoint == null || endpoint.isBlank()) && url.endsWith(SDK_DEFAULT_STREAMABLE_ENDPOINT)) {
            String baseUrl = url.substring(0, url.length() - SDK_DEFAULT_STREAMABLE_ENDPOINT.length()) + "/";
            endpoint = SDK_DEFAULT_STREAMABLE_ENDPOINT.substring(1); // "mcp" (relative)

            var builder = HttpClientStreamableHttpTransport.builder(baseUrl)
                .connectTimeout(config.getConnectTimeout())
                .endpoint(endpoint);
            return applyHeadersAndBuild(builder, config);
        }

        var builder = HttpClientStreamableHttpTransport.builder(url)
            .connectTimeout(config.getConnectTimeout());

        if (endpoint != null && !endpoint.isBlank()) {
            builder.endpoint(endpoint);
        }

        return applyHeadersAndBuild(builder, config);
    }

    private McpClientTransport applyHeadersAndBuild(HttpClientStreamableHttpTransport.Builder builder, McpServerConfig config) {
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            builder.customizeRequest(requestBuilder -> {
                for (var entry : config.getHeaders().entrySet()) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }
            });
        }
        return builder.build();
    }

    private McpClientTransport createSseTransport(McpServerConfig config) {
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

    private McpClientTransport applySseHeadersAndBuild(HttpClientSseClientTransport.Builder builder, McpServerConfig config) {
        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            builder.customizeRequest(requestBuilder -> {
                for (var entry : config.getHeaders().entrySet()) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }
            });
        }
        return builder.build();
    }

    private ToolCallResult extractToolCallResult(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return ToolCallResult.completed("Call tool completed with no content");
        }

        var sb = new StringBuilder(256);
        String imageBase64 = null;
        String imageMimeType = null;

        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(textContent.text());
            } else if (content instanceof McpSchema.ImageContent imageContent) {
                if (imageBase64 == null && imageContent.data() != null) {
                    imageBase64 = imageContent.data();
                    imageMimeType = imageContent.mimeType();
                }
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("[Image: ").append(imageContent.mimeType()).append(']');
            } else if (content instanceof McpSchema.EmbeddedResource embeddedResource) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append("[Resource: ").append(embeddedResource.resource().uri()).append(']');
            }
        }

        var textResult = sb.isEmpty() ? "Call tool completed" : sb.toString();
        var toolResult = ToolCallResult.completed(textResult);

        if (imageBase64 != null) {
            toolResult.withImage(imageBase64, imageMimeType);
        }

        return toolResult;
    }

    private String extractErrorMessage(McpSchema.CallToolResult result) {
        if (result.content() != null && !result.content().isEmpty()) {
            var firstContent = result.content().getFirst();
            if (firstContent instanceof McpSchema.TextContent textContent) {
                return "Error: " + textContent.text();
            }
        }
        return "Error: Tool execution failed";
    }
}
