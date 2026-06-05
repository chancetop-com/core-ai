package ai.core.server.workflow.executor;

import ai.core.server.domain.ScopeFrame;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.List;

/**
 * END node: the run's terminal output. Output mapping over the variable pool arrives with the variable model
 * (P2); for now it completes the branch with an empty output. A completed END is what the runner classifies
 * as a successful run.
 *
 * @author Xander
 */
public class EndExecutor implements NodeExecutor {
    @Override
    public NodeOutcome execute(WorkflowGraph graph, WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath) {
        return new NodeOutcome.Normal("{}");
    }
}
