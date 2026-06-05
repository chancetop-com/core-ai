package ai.core.server.workflow;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.TriggerType;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.run.AgentRunner;
import ai.core.server.workflow.engine.WorkflowNode;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;

import java.util.Set;

/**
 * Production {@link AgentRunGateway}: an AGENT/LLM node runs as a DECOUPLED child AgentRun. It builds a
 * snapshot-only transient AgentDefinition from the published version's embedded AgentPublishedConfig (never the
 * agent's current draft — that is the anti-drift guarantee), hands it to the existing AgentRunner, then polls
 * agent_runs for the terminal status. The child run owns its own sandbox, tools, transcript and token usage.
 *
 * @author Xander
 */
public class MongoAgentRunGateway implements AgentRunGateway {
    private static final Set<RunStatus> TERMINAL = Set.of(RunStatus.COMPLETED, RunStatus.FAILED, RunStatus.TIMEOUT, RunStatus.CANCELLED);
    private static final long POLL_INTERVAL_MS = 500;
    private static final long MAX_WAIT_MS = 2 * 60 * 60 * 1000L;   // backstop; the child run has its own timeout

    @Inject
    AgentRunner agentRunner;

    @Inject
    MongoCollection<AgentRun> agentRunCollection;

    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;

    @Override
    public String startChildRun(WorkflowRun run, WorkflowNode node, String input) {
        AgentPublishedConfig snapshot = loadSnapshot(run.versionId, node.id());
        AgentDefinition definition = transientDefinition(node, run.userId, snapshot);
        return agentRunner.run(definition, input, TriggerType.WORKFLOW, null);
    }

    @Override
    public AgentRunResult awaitResult(String childRunId) {
        long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
        while (System.currentTimeMillis() < deadline) {
            AgentRun child = agentRunCollection.get(childRunId).orElse(null);
            if (child != null && TERMINAL.contains(child.status)) {
                return child.status == RunStatus.COMPLETED
                    ? AgentRunResult.completed(child.output)
                    : AgentRunResult.failed(child.error != null ? child.error : "child run " + child.status);
            }
            sleep();
        }
        return AgentRunResult.failed("child agent run did not finish within the wait window");
    }

    @Override
    public void cancel(String childRunId) {
        agentRunner.cancel(childRunId);
    }

    private AgentPublishedConfig loadSnapshot(String versionId, String nodeId) {
        WorkflowPublishedVersion version = versionCollection.get(versionId)
            .orElseThrow(() -> new IllegalStateException("published workflow version not found: " + versionId));
        String snapshotJson = version.agentSnapshots != null ? version.agentSnapshots.get(nodeId) : null;
        if (snapshotJson == null) {
            throw new IllegalStateException("no embedded agent snapshot for node " + nodeId);
        }
        return JSON.fromJSON(AgentPublishedConfig.class, snapshotJson);
    }

    // Snapshot-only definition: AgentRunner reads everything from publishedConfig; sandbox_config is lifted to the
    // top-level field that SandboxService.getEffectiveConfig reads, so the run honors the snapshot's sandbox too.
    private static AgentDefinition transientDefinition(WorkflowNode node, String userId, AgentPublishedConfig snapshot) {
        var definition = new AgentDefinition();
        definition.id = String.valueOf(node.config().get("agent_id"));
        definition.userId = userId;
        definition.type = "LLM".equals(node.type()) ? DefinitionType.LLM_CALL : DefinitionType.AGENT;
        definition.status = AgentStatus.PUBLISHED;
        definition.publishedConfig = snapshot;
        definition.sandboxConfig = snapshot.sandboxConfig;
        return definition;
    }

    private static void sleep() {
        try {
            Thread.sleep(POLL_INTERVAL_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting child agent run", e);
        }
    }
}
