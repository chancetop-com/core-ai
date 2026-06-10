package ai.core.server.workflow.executor;

import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.tool.ToolRegistryService;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallResult;

import java.util.List;

/**
 * API tool node: invokes a registered Service-API tool, with arguments rendered from the variable pool.
 * config: {@code app_name}, {@code tool_name}, and an optional {@code arguments} JSON template. Resolves the
 * app's tools through the registry and picks the one named {@code tool_name}. A missing tool is a non-retryable
 * Fail; a call that fails is a retryable Fail so {@link RetryingNodeExecutor} can re-attempt a transient fault.
 *
 * @author Xander
 */
public class ApiToolExecutor implements NodeExecutor {
    private final ToolRegistryService toolRegistry;

    public ApiToolExecutor(ToolRegistryService toolRegistry) {
        this.toolRegistry = toolRegistry;
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        var config = ctx.node().config();
        String appName = str(config.get("app_name"));
        String toolName = str(config.get("tool_name"));
        if (appName.isBlank() || toolName.isBlank()) {
            return new NodeOutcome.Fail("api tool node missing app_name or tool_name", false);
        }
        ToolCall call = resolve(appName, toolName);
        if (call == null) {
            return new NodeOutcome.Fail("api tool not found: " + appName + "/" + toolName, false);
        }
        String arguments = renderArguments(ctx, config.get("arguments"));
        try {
            ToolCallResult result = call.execute(arguments);
            return result.isFailed()
                ? new NodeOutcome.Fail(result.getResult(), true)
                : new NodeOutcome.Normal(result.getResult());
        } catch (RuntimeException e) {
            return new NodeOutcome.Fail("api tool call failed: " + e.getMessage(), true);
        }
    }

    private ToolCall resolve(String appName, String toolName) {
        List<ToolCall> calls = toolRegistry.resolveToolRefs(List.of(ToolRef.of("api-app:" + appName, ToolSourceType.API)));
        return calls.stream().filter(call -> toolName.equals(call.getName())).findFirst().orElse(null);
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
