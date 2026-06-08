package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;

/**
 * END node: the run's terminal output. It renders an optional {@code output} template from the node config over
 * the variable pool (e.g. {@code "{{ nodes.agent1.output }}"}) so the run can surface a mapped result; with no
 * template it completes with an empty output. A completed END is what the runner classifies as a successful run.
 *
 * @author Xander
 */
public class EndExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(NodeContext ctx) {
        Object template = ctx.node().config().get("output");
        String output = template instanceof String s ? ctx.pool().render(s) : "{}";
        return new NodeOutcome.Normal(output);
    }
}
