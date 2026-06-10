package ai.core.server.workflow.executor;

import ai.core.server.tool.ToolRegistryService;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.tool.ToolCallResult;

/**
 * MCP tool node: invokes one tool on a configured MCP server, with arguments rendered from the variable pool.
 * config: {@code server_id}, {@code tool_name}, and an optional {@code arguments} JSON template (e.g.
 * {@code {"q":"{{ nodes.start.output }}"}}). Missing server/tool is a non-retryable Fail (a config error);
 * a call that fails is a retryable Fail so {@link RetryingNodeExecutor} can re-attempt a transient MCP fault.
 *
 * @author Xander
 */
public class McpToolExecutor implements NodeExecutor {
    private final ToolRegistryService toolRegistry;

    public McpToolExecutor(ToolRegistryService toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        var config = ctx.node().config();
        String serverId = str(config.get("server_id"));
        String toolName = str(config.get("tool_name"));
        if (serverId.isBlank() || toolName.isBlank()) {
            return new NodeOutcome.Fail("mcp tool node missing server_id or tool_name", false);
        }
        String arguments = renderArguments(ctx, config.get("arguments"));
        try {
            ToolCallResult result = toolRegistry.callMcpServerTool(serverId, toolName, arguments);
            return result.isFailed()
                ? new NodeOutcome.Fail(result.getResult(), true)
                : new NodeOutcome.Normal(result.getResult());
        } catch (RuntimeException e) {
            return new NodeOutcome.Fail("mcp tool call failed: " + e.getMessage(), true);
        }
    }

    private String renderArguments(NodeContext ctx, Object arguments) {
        if (!(arguments instanceof String template) || template.isBlank()) {
            return "{}";
        }
        return ctx.pool().renderJson(template);   // values land in JSON string positions -> escape, don't inject
    }

    private static String str(Object value) {
        return value == null ? "" : String.valueOf(value);
    }
}
