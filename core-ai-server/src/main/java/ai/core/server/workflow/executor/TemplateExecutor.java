package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;

/**
 * TEMPLATE ("Text") node: outputs a fixed or templated string — pure in-JVM render over the variable pool, no
 * sandbox, no LLM, no external call. Its job is producing a deterministic value: a canned reply on a branch,
 * text assembly/formatting, or composing a prompt for a downstream node. config: {@code template} (literal text
 * plus optional {@code {{ selector }}} references). Output is the rendered string (navigable downstream if it
 * renders to a JSON object).
 *
 * @author Xander
 */
public class TemplateExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(NodeContext ctx) {
        Object template = ctx.node().config().get("template");
        String rendered = template instanceof String text ? ctx.pool().render(text) : "";
        return new NodeOutcome.Normal(rendered == null ? "" : rendered);
    }
}
