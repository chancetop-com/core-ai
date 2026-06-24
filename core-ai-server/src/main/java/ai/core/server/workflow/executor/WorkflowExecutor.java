package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.server.workflow.WorkflowRunGateway;
import core.framework.json.JSON;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * WORKFLOW node: call another published workflow as a DECOUPLED child run. It renders the field-level input
 * mappings over the variable pool into the child's START input, enforces the runtime depth cap (the primary
 * recursion backstop), submits the child run, and parks via {@link NodeOutcome.Suspended} — it never blocks.
 * The child's terminal callback later settles this node and wakes the parent (WorkflowRunner.wakeParent).
 *
 * <p>config: {@code source_workflow_id}, {@code version_id} (the pinned immutable published version of the child),
 * and {@code input_mappings} ({childStartField: templateExpr}). With no mappings the parent run input passes
 * through unchanged.
 *
 * @author Xander
 */
public class WorkflowExecutor implements NodeExecutor {
    private final WorkflowRunGateway gateway;
    private final int maxDepth;

    public WorkflowExecutor(WorkflowRunGateway gateway, int maxDepth) {
        this.gateway = gateway;
        this.maxDepth = maxDepth;
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        int parentDepth = ctx.run().depth != null ? ctx.run().depth : 0;
        int childDepth = parentDepth + 1;
        if (childDepth > maxDepth) {
            // non-retryable: re-running cannot reduce depth. Backstops any cycle (incl. ones a stale snapshot hid).
            return new NodeOutcome.Fail("workflow nesting too deep (depth " + childDepth + " > max " + maxDepth + ")", false);
        }
        String input = renderInput(ctx);
        String childRunId = gateway.submitChildRun(ctx.run(), ctx.node(), input, childDepth);
        return new NodeOutcome.Suspended(childRunId);
    }

    private String renderInput(NodeContext ctx) {
        Object mappings = ctx.node().config().get("input_mappings");
        if (!(mappings instanceof Map<?, ?> map) || map.isEmpty()) {
            return ctx.run().input;   // passthrough when nothing is mapped
        }
        var rendered = new LinkedHashMap<String, Object>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String field = String.valueOf(entry.getKey());
            String template = String.valueOf(entry.getValue());
            rendered.put(field, ctx.pool().render(template));   // M1: string render; M2 adds schema type coercion
        }
        return JSON.toJSON(rendered);
    }
}
