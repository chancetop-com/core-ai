package ai.core.server.workflow.executor;

import ai.core.server.workflow.AgentRunGateway;
import ai.core.server.workflow.AgentRunResult;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;

/**
 * AGENT / LLM node: a DECOUPLED child run, not the agent loop inlined. Per the design's submit/await/collect
 * contract, it starts a child AgentRun from the node's embedded published snapshot, waits for it to finish, and
 * maps the result to a node outcome — carrying the child run id so the two-layer run stays linked. The same
 * executor serves both AGENT and LLM nodes; the gateway picks the DefinitionType from the node type.
 *
 * <p>The node's input is an optional {@code input} template in the config, rendered over the variable pool (e.g.
 * {@code "{{ nodes.start.output }}"}) so a node can consume an upstream node's output; with no template the raw
 * run input is passed through. Forwarding a workflow cancel to the child run lands with the agent-stack wiring.
 *
 * @author Xander
 */
public class AgentExecutor implements NodeExecutor {
    private final AgentRunGateway gateway;

    public AgentExecutor(AgentRunGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public NodeOutcome execute(NodeContext ctx) {
        Object template = ctx.node().config().get("input");
        String input = template instanceof String s ? ctx.pool().render(s) : ctx.run().input;
        String childRunId = gateway.startChildRun(ctx.run(), ctx.node(), input);
        AgentRunResult result = gateway.awaitResult(childRunId);
        return result.completed()
            ? new NodeOutcome.Normal(result.output(), childRunId, result.artifacts())
            // retryable: a child-run failure is treated as transient so RetryingNodeExecutor can start a fresh
            // child run; an exhausted retry budget then surfaces this as the terminal node failure.
            : new NodeOutcome.Fail(result.error(), true, childRunId);
    }
}
