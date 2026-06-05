package ai.core.server.workflow.executor;

import ai.core.server.domain.ScopeFrame;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.List;

/**
 * START node: the run entry. It exposes the run input as the node output. Input-schema validation and seeding
 * the typed variable pool (env/sys/conversation) arrive with the variable model (P2); for now it passes the
 * raw run input through.
 *
 * @author Xander
 */
public class StartExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(WorkflowGraph graph, WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath) {
        return new NodeOutcome.Normal(run.input != null ? run.input : "{}");
    }
}
