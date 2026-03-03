package ai.core.tool.tools;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.mcp.client.McpServerConfig;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.tool.mcp.McpToolCalls;
import core.framework.json.JSON;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Tool that allows the agent to add an MCP server at runtime.
 * New MCP tools are registered and available in subsequent turns.
 *
 * @author xander
 */
public class AddMcpServerTool extends ToolCall {

    public static final String TOOL_NAME = "add_mcp_server";

    private static final Logger LOGGER = LoggerFactory.getLogger(AddMcpServerTool.class);

    private static final String TOOL_DESC = """
            Add and connect to an MCP (Model Context Protocol) server at runtime.
            After connecting, the server's tools become available for use.

            For STDIO servers, provide 'command' and optionally 'args'.
            For HTTP servers, provide 'url'.

            Examples:
            - STDIO: {"name": "filesystem", "command": "npx", "args": "-y @modelcontextprotocol/server-filesystem /tmp"}
            - HTTP: {"name": "remote", "url": "http://localhost:3000/mcp"}
            """;

    public static Builder builder() {
        return new Builder();
    }

    private Consumer<List<ToolCall>> toolRegistrar;

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        try {
            var args = JSON.fromJSON(Map.class, arguments);
            String name = (String) args.get("name");
            if (name == null || name.isBlank()) {
                return ToolCallResult.failed("'name' is required");
            }
            McpServerConfig config = buildConfig(name, args);
            return connectAndRegister(name, config, startTime);
        } catch (Exception e) {
            LOGGER.warn("Failed to add MCP server: {}", e.getMessage());
            return ToolCallResult.failed("Failed to add MCP server: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    @SuppressWarnings("unchecked")
    private McpServerConfig buildConfig(String name, Map<?, ?> args) {
        if (args.containsKey("command")) {
            var builder = McpServerConfig.stdio((String) args.get("command")).name(name);
            if (args.get("args") instanceof String argsStr) {
                builder.args(argsStr.split("\\s+"));
            } else if (args.get("args") instanceof List<?> argsList) {
                builder.args(argsList.stream().map(Object::toString).toList());
            }
            return builder.build();
        }
        if (args.containsKey("url")) {
            return McpServerConfig.http((String) args.get("url")).name(name).build();
        }
        throw new IllegalArgumentException("Either 'command' (STDIO) or 'url' (HTTP) is required");
    }

    private ToolCallResult connectAndRegister(String name, McpServerConfig config, long startTime) {
        var manager = McpClientManagerRegistry.getManager();
        if (manager == null) {
            manager = McpClientManager.of(config);
            McpClientManagerRegistry.setManager(manager);
        } else {
            manager.addServer(config);
        }
        var client = manager.getClient(name);
        var tools = client.listTools();
        var mcpToolCalls = McpToolCalls.from(manager, List.of(name), null);
        if (toolRegistrar != null) {
            toolRegistrar.accept(new ArrayList<>(mcpToolCalls));
        }
        return ToolCallResult.completed("Connected to MCP server '" + name + "'. "
                        + tools.size() + " tools now available: "
                        + tools.stream().map(t -> t.name).toList())
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("server", name)
                .withStats("toolCount", tools.size());
    }

    public static class Builder extends ToolCall.Builder<Builder, AddMcpServerTool> {
        private Consumer<List<ToolCall>> toolRegistrar;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder toolRegistrar(Consumer<List<ToolCall>> registrar) {
            this.toolRegistrar = registrar;
            return this;
        }

        public AddMcpServerTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "name", "Server name identifier").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "command", "Command to launch STDIO server (e.g. npx, uvx)"),
                    ToolCallParameters.ParamSpec.of(String.class, "args", "Command arguments as space-separated string"),
                    ToolCallParameters.ParamSpec.of(String.class, "url", "URL for HTTP/SSE server")
            ));
            var tool = new AddMcpServerTool();
            build(tool);
            tool.toolRegistrar = this.toolRegistrar;
            return tool;
        }
    }
}
