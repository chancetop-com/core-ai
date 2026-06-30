package ai.core.server.workflow.executor;

import ai.core.server.domain.WorkflowNodeTraceMetadata;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.workflow.AgentRunGateway;
import ai.core.server.workflow.AgentRunResult;
import ai.core.server.workflow.ArtifactStaging;
import ai.core.server.workflow.NodeContext;
import ai.core.server.workflow.NodeExecutor;
import ai.core.server.workflow.NodeOutcome;
import ai.core.server.workflow.StartedAgentRun;
import ai.core.server.workflow.engine.WorkflowNode;

import java.util.List;

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
 * <p>File flow (design §5.3.2): for a sandboxed AGENT node, upstream artifacts the input template references are
 * staged into the child sandbox by the platform, and the template renders each artifact with its local
 * {@code path} — the agent reads the file directly instead of fetching a URL. LLM nodes have no sandbox, so they
 * keep the url-in-prompt fallback (plain render, no staging).
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
        String input;
        List<SandboxService.StagedFile> stagedFiles = List.of();
        if (template instanceof String s) {
            if ("AGENT".equals(ctx.node().type())) {
                stagedFiles = ArtifactStaging.scanTemplate(s, ctx.pool());
            }
            input = (stagedFiles.isEmpty() ? ctx.pool() : ctx.pool().stagedView()).render(s);
        } else {
            input = ctx.run().input;
        }
        StartedAgentRun child = gateway.startChildRun(ctx.run(), ctx.node(), input, stagedFiles);
        AgentRunResult result = gateway.awaitResult(child.runId());
        WorkflowNodeTraceMetadata metadata = traceMetadata(ctx.node(), child, result);
        return result.completed()
            ? new NodeOutcome.Normal(result.output(), child.runId(), result.artifacts(), metadata)
            // retryable: a child-run failure is treated as transient so RetryingNodeExecutor can start a fresh
            // child run; an exhausted retry budget then surfaces this as the terminal node failure.
            : new NodeOutcome.Fail(result.error(), true, child.runId(), metadata);
    }

    private static WorkflowNodeTraceMetadata traceMetadata(WorkflowNode node, StartedAgentRun child, AgentRunResult result) {
        var metadata = new WorkflowNodeTraceMetadata();
        metadata.agentId = str(node.config().get("agent_id"));
        metadata.agentName = str(node.config().get("agent_name"));
        metadata.model = child.model();
        metadata.multiModalModel = child.multiModalModel();
        metadata.childTraceId = result.traceId();
        metadata.childStatus = result.status() != null ? result.status().name() : null;
        metadata.tokenUsage = result.tokenUsage();
        return metadata;
    }

    private static String str(Object value) {
        if (value == null) {
            return null;
        }
        String string = String.valueOf(value);
        return string.isBlank() ? null : string;
    }
}
