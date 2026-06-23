package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;

/**
 * END node: the run's single terminal output. Composes its output via {@link OutputComposer} — an explicit
 * {@code output} template, or (with no template) a pass-through of the one completed branch / a merge of parallel
 * branches. A completed END is what the runner classifies as a successful run and bubbles up to run.output.
 *
 * <p>Its artifacts are the run's DELIVERABLES (see {@link OutputComposer#composeDeliverables}): an explicit
 * {@code artifacts} selector list is authoritative; otherwise the union of its completed predecessors' artifacts
 * plus any files the {@code output} template references via {@code {{ nodes.<id>.artifacts }}}. So an intermediate
 * node's file IS lifted to the run result when the output references it — otherwise it stays visible per node-run
 * (trace level) only.
 *
 * @author Xander
 */
public class EndExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(NodeContext ctx) {
        return new NodeOutcome.Normal(OutputComposer.compose(ctx), null, OutputComposer.composeDeliverables(ctx));
    }
}
