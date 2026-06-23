package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;

/**
 * AGGREGATOR node: the mid-graph twin of END. Same {@link OutputComposer} operation — combine the completed
 * predecessors (or render an explicit {@code output} template) — but its result flows downstream instead of
 * terminating the run. Its real value is coalescing after a conditional (only one branch ran) so a downstream
 * node can reference a single variable; for pure parallel a downstream template can already pull each branch, so
 * the aggregator there is just convenience.
 *
 * <p>Its artifacts ({@link OutputComposer#composeArtifacts}) are the predecessor union plus any files its
 * {@code output} template references, so files pulled through an aggregator propagate downstream too.
 *
 * @author Xander
 */
public class AggregatorExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(NodeContext ctx) {
        return new NodeOutcome.Normal(OutputComposer.compose(ctx), null, OutputComposer.composeArtifacts(ctx));
    }
}
