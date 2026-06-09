package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;

/**
 * END node: the run's single terminal output. Composes its output via {@link OutputComposer} — an explicit
 * {@code output} template, or (with no template) a pass-through of the one completed branch / a merge of parallel
 * branches. A completed END is what the runner classifies as a successful run and bubbles up to run.output.
 *
 * @author Xander
 */
public class EndExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(NodeContext ctx) {
        return new NodeOutcome.Normal(OutputComposer.compose(ctx));
    }
}
