package ai.core.tool.tools;

import ai.core.mcp.client.McpClientManager;
import ai.core.mcp.client.McpClientManagerRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
* author cyril
* description
* createTime  2026/6/30
**/
public class GetMcpServerInstructionsTool extends ToolCall {

    public static final String TOOL_NAME = "get_mcp_server_instructions";

    private static final Logger LOGGER = LoggerFactory.getLogger(GetMcpServerInstructionsTool.class);

    private static final String TOOL_DESC = """
            Get usage instructions for connected MCP servers.
            Call without arguments to list all connected servers.
            Call with a server name to get detailed instructions on how to use that server's tools correctly,
            including recommended usage patterns, anti-patterns, and limitations.
            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String arguments) {
        long startTime = System.currentTimeMillis();
        var manager = McpClientManagerRegistry.getManager();
        if (manager == null) {
            return ToolCallResult.completed("No MCP servers are configured.")
                    .withDuration(System.currentTimeMillis() - startTime);
        }

        var args = parseArguments(arguments);
        String serverName = getStringValue(args, "server_name");

        if (serverName == null || serverName.isBlank()) {
            return listServers(manager, startTime);
        }
        return getInstructions(manager, serverName, startTime);
    }

    private ToolCallResult listServers(McpClientManager manager, long startTime) {
        var allClients = manager.getAllClients();
        if (allClients.isEmpty()) {
            return ToolCallResult.completed("No MCP servers are currently connected.")
                    .withDuration(System.currentTimeMillis() - startTime);
        }

        var sb = new StringBuilder("Connected MCP servers:\n");
        for (var entry : allClients.entrySet()) {
            String name = entry.getKey();
            sb.append("- ").append(name);
            try {
                String instructions = entry.getValue().getMcpClient().getServerInstructions();
                if (instructions != null && !instructions.isBlank()) {
                    sb.append(" (has usage instructions)");
                }
            } catch (Exception e) {
                LOGGER.debug("Could not check instructions for server: {}", name, e);
            }
            sb.append('\n');
        }
        sb.append("\nCall get_mcp_server_instructions with server_name to get detailed instructions for a specific server.");
        return ToolCallResult.completed(sb.toString())
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("serverCount", allClients.size());
    }

    private ToolCallResult getInstructions(McpClientManager manager, String serverName, long startTime) {
        var client = manager.getAllClients().get(serverName);
        if (client == null) {
            var serverNames = manager.getServerNames();
            return ToolCallResult.failed("Server not found: " + serverName
                    + ". Available servers: " + serverNames)
                    .withDuration(System.currentTimeMillis() - startTime);
        }

        try {
            String instructions = client.getMcpClient().getServerInstructions();
            if (instructions == null || instructions.isBlank()) {
                return ToolCallResult.completed("Server '" + serverName + "' does not provide usage instructions.")
                        .withDuration(System.currentTimeMillis() - startTime);
            }
            return ToolCallResult.completed("# Server: " + serverName + "\n" + instructions.strip())
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("server", serverName);
        } catch (Exception e) {
            LOGGER.warn("Failed to get instructions from MCP server: {}", serverName, e);
            return ToolCallResult.failed("Failed to get instructions from server '" + serverName + "': " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    public static class Builder extends ToolCall.Builder<Builder, GetMcpServerInstructionsTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public GetMcpServerInstructionsTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "server_name",
                            "Name of the MCP server to get instructions for. Omit to list all connected servers.")
            ));
            var tool = new GetMcpServerInstructionsTool();
            build(tool);
            return tool;
        }
    }
}
