package ai.core.mcp.client;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.api.mcp.schema.tool.Tool;
import ai.core.utils.JsonUtil;
import ai.core.utils.SystemUtil;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientSseClientTransport;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.ServerParameters;
import io.modelcontextprotocol.client.transport.StdioClientTransport;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * @author stephen
 */
public class McpClientService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpClientService.class);

    private final McpSyncClient client;
    private final McpClientTransport transport;
    private final String serverName;
    private Process stdioProcess;

    public McpClientService(McpServerConfig config) {
        this.serverName = config.getName();
        this.transport = createTransport(config);
        this.client = createClient(transport, config);
    }

    public List<Tool> listTools(List<String> namespaces) {
        var result = client.listTools();
        var tools = convertTools(result.tools());

        if (namespaces == null || namespaces.isEmpty()) {
            return tools;
        }

        return tools.stream()
            .filter(tool -> namespaces.stream()
                .anyMatch(ns -> tool.name.startsWith(ns + "_") || tool.name.startsWith(ns + "/")))
            .toList();
    }

    public List<Tool> listTools() {
        return listTools(null);
    }

    public String callTool(String name, String text) {
        return callTool(name, JsonUtil.toMap(text));
    }

    public String callTool(String name, Map<String, Object> arguments) {
        var request = new McpSchema.CallToolRequest(getRealName(serverName, name), arguments);
        var result = client.callTool(request);

        if (result.isError() != null && result.isError()) {
            return extractErrorMessage(result);
        }

        return extractResultContent(result);
    }

    private String getRealName(String serverName, String name) {
        return name.substring(serverName.length() + 1);
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

    @Override
    public void close() {
        LOGGER.info("Closing MCP client: {}", serverName);

        // First, try to close gracefully
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

        // Force destroy the subprocess and all descendants if still alive
        if (stdioProcess != null) {
            forceDestroyProcessTree(stdioProcess);
        } else {
            LOGGER.debug("No subprocess to destroy for: {}", serverName);
        }
        LOGGER.info("MCP client closed: {}", serverName);
    }

    private void forceDestroyProcessTree(Process process) {
        if (!process.isAlive()) {
            LOGGER.debug("Process already terminated for: {}", serverName);
            return;
        }

        long pid = process.pid();
        LOGGER.info("Force destroying MCP subprocess tree: server={}, pid={}", serverName, pid);

        // First destroy all descendants
        process.descendants().forEach(ph -> {
            LOGGER.debug("Destroying descendant process: pid={}", ph.pid());
            ph.destroyForcibly();
        });

        // Then destroy the main process
        process.destroyForcibly();

        // Wait a bit for the process to terminate
        try {
            boolean terminated = process.waitFor(3, java.util.concurrent.TimeUnit.SECONDS);
            if (!terminated) {
                LOGGER.warn("Process did not terminate within timeout: server={}, pid={}", serverName, pid);
                // On Windows, use taskkill as fallback
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
            LOGGER.info("Using taskkill to forcefully terminate process tree: pid={}", pid);
            var processBuilder = new ProcessBuilder("taskkill", "/F", "/T", "/PID", String.valueOf(pid));
            processBuilder.redirectErrorStream(true);
            var killProcess = processBuilder.start();
            killProcess.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            LOGGER.warn("Failed to execute taskkill: {}", e.getMessage());
        }
    }

    private McpSyncClient createClient(McpClientTransport transport, McpServerConfig config) {
        var syncClient = McpClient.sync(transport)
            .requestTimeout(Duration.ofSeconds(30))
            .build();
        syncClient.initialize();
        // Extract process reference after initialization for proper cleanup
        if (transport instanceof StdioClientTransport stdioTransport) {
            extractProcessFromTransport(stdioTransport);
        }
        LOGGER.info("MCP client initialized: name={}, transport={}", config.getName(), config.getTransportType());
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
                        LOGGER.info("Extracted process PID {} from StdioClientTransport field '{}' for cleanup",
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

        // On Windows, commands like npx, npm need to be executed via cmd.exe
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

        return new StdioClientTransport(paramsBuilder.build(), McpJsonMapper.createDefault());
    }

    private boolean isWindowsScriptCommand(String command) {
        var cmd = command.toLowerCase(Locale.ROOT);
        return cmd.equals("npx") || cmd.equals("npm") || cmd.equals("node")
                || cmd.equals("pnpm") || cmd.equals("yarn") || cmd.equals("bun")
                || cmd.endsWith(".cmd") || cmd.endsWith(".bat");
    }

    private McpClientTransport createStreamableHttpTransport(McpServerConfig config) {
        var builder = HttpClientStreamableHttpTransport.builder(config.getUrl())
            .connectTimeout(Duration.ofSeconds(10));

        if (config.getEndpoint() != null && !config.getEndpoint().isBlank()) {
            builder.endpoint(config.getEndpoint());
        }

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
        var builder = HttpClientSseClientTransport.builder(config.getUrl())
            .connectTimeout(Duration.ofSeconds(10));

        if (config.getEndpoint() != null && !config.getEndpoint().isBlank()) {
            builder.sseEndpoint(config.getEndpoint());
        }

        if (config.getHeaders() != null && !config.getHeaders().isEmpty()) {
            builder.customizeRequest(requestBuilder -> {
                for (var entry : config.getHeaders().entrySet()) {
                    requestBuilder.header(entry.getKey(), entry.getValue());
                }
            });
        }

        return builder.build();
    }

    private List<Tool> convertTools(List<McpSchema.Tool> mcpTools) {
        if (mcpTools == null) {
            return List.of();
        }
        return mcpTools.stream()
            .map(this::convertTool)
            .toList();
    }

    private Tool convertTool(McpSchema.Tool mcpTool) {
        var tool = new Tool();
        tool.name = mcpTool.name();
        tool.description = mcpTool.description();
        tool.inputSchema = convertInputSchema(mcpTool.inputSchema());
        return tool;
    }

    private JsonSchema convertInputSchema(McpSchema.JsonSchema jsonSchema) {
        if (jsonSchema == null) {
            return null;
        }
        var schema = new JsonSchema();
        schema.type = convertPropertyType(jsonSchema.type());
        schema.properties = convertProperties(jsonSchema.properties());
        schema.required = jsonSchema.required();
        return schema;
    }

    private JsonSchema.PropertyType convertPropertyType(String type) {
        if (type == null) {
            return JsonSchema.PropertyType.OBJECT;
        }
        return switch (type.toLowerCase(Locale.ROOT)) {
            case "string" -> JsonSchema.PropertyType.STRING;
            case "number" -> JsonSchema.PropertyType.NUMBER;
            case "integer" -> JsonSchema.PropertyType.INTEGER;
            case "boolean" -> JsonSchema.PropertyType.BOOLEAN;
            case "array" -> JsonSchema.PropertyType.ARRAY;
            case "null" -> JsonSchema.PropertyType.NULL;
            default -> JsonSchema.PropertyType.OBJECT;
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, JsonSchema> convertProperties(Map<String, Object> properties) {
        if (properties == null) {
            return null;
        }
        var result = new HashMap<String, JsonSchema>();
        for (var entry : properties.entrySet()) {
            if (entry.getValue() instanceof Map<?, ?> propMap) {
                result.put(entry.getKey(), convertPropertySchema((Map<String, Object>) propMap));
            }
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    private JsonSchema convertPropertySchema(Map<String, Object> propMap) {
        var prop = new JsonSchema();
        prop.type = convertPropertyType((String) propMap.get("type"));
        prop.description = (String) propMap.get("description");
        prop.format = (String) propMap.get("format");

        if (propMap.get("enum") instanceof List<?> enumList) {
            prop.enums = (List<String>) enumList;
        }

        if (propMap.get("required") instanceof List<?> requiredList) {
            prop.required = (List<String>) requiredList;
        }

        if (propMap.get("properties") instanceof Map<?, ?> nestedProps) {
            prop.properties = convertProperties((Map<String, Object>) nestedProps);
        }

        if (propMap.get("items") instanceof Map<?, ?> itemsMap) {
            prop.items = convertPropertySchema((Map<String, Object>) itemsMap);
        }

        if (propMap.get("additionalProperties") instanceof Boolean additionalProps) {
            prop.additionalProperties = additionalProps;
        }

        return prop;
    }

    private String extractResultContent(McpSchema.CallToolResult result) {
        if (result.content() == null || result.content().isEmpty()) {
            return "Call tool completed with no content";
        }

        var sb = new StringBuilder(256);
        for (var content : result.content()) {
            if (content instanceof McpSchema.TextContent textContent) {
                if (!sb.isEmpty()) {
                    sb.append('\n');
                }
                sb.append(textContent.text());
            } else if (content instanceof McpSchema.ImageContent imageContent) {
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
        return sb.isEmpty() ? "Call tool completed with unknown content type" : sb.toString();
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
