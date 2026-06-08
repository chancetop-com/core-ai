package ai.core.server.workflow.executor;

import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;

/**
 * START node: the run entry. It exposes the run input as the node output, so downstream nodes read it via
 * {@code sys.input} or {@code nodes.<startId>.output}. Input-schema validation arrives with the full variable
 * model (P2); for now it passes the raw run input through.
 *
 * @author Xander
 */
public class StartExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(NodeContext ctx) {
        return new NodeOutcome.Normal(ctx.run().input != null ? ctx.run().input : "{}");
    }
}
