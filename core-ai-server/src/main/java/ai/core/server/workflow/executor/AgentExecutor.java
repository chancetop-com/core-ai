package ai.core.server.workflow.executor;

import ai.core.server.domain.ScopeFrame;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.AgentRunGateway;
import ai.core.server.workflow.AgentRunResult;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.server.workflow.engine.WorkflowGraph;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.List;

/**
 * AGENT / LLM node: a DECOUPLED child run, not the agent loop inlined. Per the design's submit/await/collect
 * contract, it starts a child AgentRun from the node's embedded published snapshot, waits for it to finish, and
 * maps the result to a node outcome — carrying the child run id so the two-layer run stays linked. The same
 * executor serves both AGENT and LLM nodes; the gateway picks the DefinitionType from the node type.
 *
 * <p>Input mapping over the variable pool lands in P2; for now the run input is passed through. Forwarding a
 * workflow cancel to the child run, and persisting the child link before awaiting, land with the agent-stack
 * wiring (the Mongo gateway) once the server module compiles.
 *
 * @author Xander
 */
public class AgentExecutor implements NodeExecutor {
    private final AgentRunGateway gateway;

    public AgentExecutor(AgentRunGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public NodeOutcome execute(WorkflowGraph graph, WorkflowRun run, WorkflowNode node, List<ScopeFrame> scopePath) {
        String input = run.input;   // P2: resolve the input_template over the variable pool
        String childRunId = gateway.startChildRun(run, node, input);
        AgentRunResult result = gateway.awaitResult(childRunId);
        return result.completed()
            ? new NodeOutcome.Normal(result.output(), childRunId)
            : new NodeOutcome.Fail(result.error(), false, childRunId);
    }
}
