package ai.core.mcp.server;

import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.utils.JsonUtil;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpStatelessServerFeatures;
import io.modelcontextprotocol.server.McpStatelessSyncServer;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class McpServerService implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(McpServerService.class);

    private final McpStreamableServerTransportProvider transportProvider;
    private final McpStatelessSyncServer server;
    private final String serverName;
    private final String serverVersion;

    private McpServerToolLoader toolLoader;
    private boolean toolsLoaded = false;

    public McpServerService(String serverName, String serverVersion) {
        this.serverName = serverName;
        this.serverVersion = serverVersion;
        this.transportProvider = new McpStreamableServerTransportProvider();
        this.server = McpServer.sync(transportProvider)
                .serverInfo(serverName, serverVersion)
                .capabilities(McpSchema.ServerCapabilities.builder().tools(true).build())
                .build();
        LOGGER.info("MCP Server initialized: name={}, version={}", serverName, serverVersion);
    }

    public void setToolLoader(McpServerToolLoader toolLoader) {
        this.toolLoader = toolLoader;
        loadTools();
    }

    public void loadTools() {
        if (toolLoader == null) {
            throw new IllegalStateException("Tool loader not configured");
        }
        if (toolsLoaded) {
            LOGGER.warn("Tools already loaded, skipping reload");
            return;
        }

        var tools = toolLoader.load();
//        var namespaces = toolLoader.defaultNamespaces();

        for (var tool : tools) {
            registerTool(tool);
        }
        toolsLoaded = true;
        LOGGER.info("Loaded {} tools into MCP server", tools.size());
    }

    public void reload() {
        toolsLoaded = false;
        loadTools();
    }

    @SuppressWarnings("unchecked")
    private void registerTool(ToolCall toolCall) {
        var inputSchema = toolCall.toJsonSchema();
        var schemaMap = inputSchema != null ? (Map<String, Object>) JsonUtil.toMap(inputSchema) : Map.of();

        // Convert to SDK JsonSchema format using record constructor
        // JsonSchema(String type, Map<String,Object> properties, List<String> required, Boolean additionalProperties, Map<String,Object> defs, Map<String,Object> meta)
        var sdkInputSchema = new McpSchema.JsonSchema(
                "object",
                (Map<String, Object>) schemaMap.get("properties"),
                schemaMap.containsKey("required") ? (List<String>) schemaMap.get("required") : null,
                null,  // additionalProperties
                null,  // defs
                null   // meta
        );

        var tool = McpSchema.Tool.builder()
                .name(toolCall.getName())
                .description(toolCall.getDescription())
                .inputSchema(sdkInputSchema)
                .build();

        var toolSpec = McpStatelessServerFeatures.SyncToolSpecification.builder()
                .tool(tool)
                .callHandler((context, req) -> {
                    try {
                        var jsonArgs = req != null ? JsonUtil.toJsonNotOnlyPublic(req.arguments()) : "{}";
                        var result = toolCall.execute(jsonArgs, ExecutionContext.empty());
                        var textContent = new McpSchema.TextContent(result.toResultForLLM());
                        return McpSchema.CallToolResult.builder().content(List.of(textContent)).isError(false).build();
                    } catch (Exception e) {
                        LOGGER.error("Error executing tool: {}", toolCall.getName(), e);
                        var errorContent = new McpSchema.TextContent("Error: " + e.getMessage());
                        return McpSchema.CallToolResult.builder().content(List.of(errorContent)).isError(true).build();
                    }
                })
                .build();

        server.addTool(toolSpec);
        LOGGER.debug("Registered tool: {}", toolCall.getName());
    }

    public McpStreamableServerTransportProvider getTransportProvider() {
        return transportProvider;
    }

    public McpStatelessSyncServer getServer() {
        return server;
    }

    public String getServerName() {
        return serverName;
    }

    public String getServerVersion() {
        return serverVersion;
    }

    @Override
    public void close() {
        LOGGER.info("Closing MCP Server: {}", serverName);
        try {
            server.close();
        } catch (Exception e) {
            LOGGER.warn("Error closing MCP server", e);
        }
    }
}
