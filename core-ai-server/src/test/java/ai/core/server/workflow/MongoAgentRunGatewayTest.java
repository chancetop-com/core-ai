package ai.core.server.workflow;

import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.workflow.engine.WorkflowNode;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class MongoAgentRunGatewayTest {
    @Test
    void transientDefinitionCarriesWorkflowNodeNameForTraceIdentity() {
        var snapshot = new AgentPublishedConfig();
        var node = new WorkflowNode("summarize", "AGENT", List.of(), Map.of(
            "agent_id", "agent-123",
            "agent_name", "Support Summarizer"));

        var definition = MongoAgentRunGateway.transientDefinition(node, "user-1", snapshot);

        assertEquals("agent-123", definition.id);
        assertEquals("Support Summarizer", definition.name);
        assertEquals("user-1", definition.userId);
        assertEquals(DefinitionType.AGENT, definition.type);
        assertEquals(snapshot, definition.publishedConfig);
    }

    @Test
    void queuedPublishedRunRevalidatesUnsafeVersionBeforeAgentExecution() {
        var snapshot = new AgentPublishedConfig();
        snapshot.systemPromptId = "legacy-live-prompt";
        var version = new WorkflowPublishedVersion();
        version.id = "workflow-1:v1";
        version.agentSnapshots = Map.of("agent-node", JSON.toJSON(snapshot));
        @SuppressWarnings("unchecked")
        MongoCollection<WorkflowPublishedVersion> versions = mock(MongoCollection.class);
        when(versions.get(version.id)).thenReturn(Optional.of(version));
        var validator = mock(WorkflowPrivateAgentSafetyValidator.class);
        var blocked = new WorkflowValidationException(List.of("node agent-node has an unsafe legacy snapshot"));
        doThrow(blocked).when(validator).requireSafe(version);
        var gateway = new MongoAgentRunGateway(validator);
        gateway.versionCollection = versions;
        gateway.agentRunner = mock(ai.core.server.run.AgentRunner.class);
        var run = new WorkflowRun();
        run.versionId = version.id;
        run.preview = Boolean.FALSE;
        var node = new WorkflowNode("agent-node", "AGENT", List.of(), Map.of("agent_id", "agent-1"));

        var actual = assertThrows(WorkflowValidationException.class,
            () -> gateway.startChildRun(run, node, "{}", List.of()));

        assertSame(blocked, actual);
        verifyNoInteractions(gateway.agentRunner);
    }

    @Test
    void previewRejectsLegacySnapshotWithUnvalidatedSkillsBeforeAgentExecution() {
        var snapshot = new AgentPublishedConfig();
        snapshot.skillIds = List.of("historical-skill");
        var version = new WorkflowPublishedVersion();
        version.id = "workflow-1:v1";
        version.agentSnapshots = Map.of("agent-node", JSON.toJSON(snapshot));
        @SuppressWarnings("unchecked")
        MongoCollection<WorkflowPublishedVersion> versions = mock(MongoCollection.class);
        when(versions.get(version.id)).thenReturn(Optional.of(version));
        var validator = mock(WorkflowPrivateAgentSafetyValidator.class);
        var gateway = new MongoAgentRunGateway(validator);
        gateway.versionCollection = versions;
        gateway.agentRunner = mock(ai.core.server.run.AgentRunner.class);
        var run = new WorkflowRun();
        run.versionId = version.id;
        run.preview = Boolean.TRUE;
        var node = new WorkflowNode("agent-node", "AGENT", List.of(), Map.of("agent_id", "agent-1"));

        var error = assertThrows(IllegalStateException.class,
            () -> gateway.startChildRun(run, node, "{}", List.of()));

        assertEquals("agent snapshot contains unvalidated skills", error.getMessage());
        verifyNoInteractions(gateway.agentRunner);
        verifyNoInteractions(validator);
    }
}
