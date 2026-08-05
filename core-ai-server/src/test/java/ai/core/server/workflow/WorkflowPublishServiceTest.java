package ai.core.server.workflow;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowDefinitionStatus;
import ai.core.server.domain.WorkflowPublishedVersion;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkflowPublishServiceTest {
    private WorkflowPublishService publishService;
    private MongoCollection<WorkflowPublishedVersion> versionCollection;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        MongoCollection<WorkflowDefinition> definitionCollection = mock(MongoCollection.class);
        MongoCollection<AgentDefinition> agentCollection = mock(MongoCollection.class);
        versionCollection = mock(MongoCollection.class);
        var workflow = workflow();
        var agent = agent();
        when(definitionCollection.get(workflow.id)).thenReturn(Optional.of(workflow));
        when(agentCollection.get(agent.id)).thenReturn(Optional.of(agent));
        var snapshots = new WorkflowAgentSnapshotService();
        snapshots.agentDefinitionCollection = agentCollection;
        publishService = new WorkflowPublishService();
        publishService.definitionCollection = definitionCollection;
        publishService.versionCollection = versionCollection;
        publishService.agentSnapshotService = snapshots;
    }

    @Test
    void privatePreviewRejectsLlmCallToolWithoutPersistingVersionSnapshotsOrProvenance() {
        WorkflowValidationException error = assertThrows(WorkflowValidationException.class,
            () -> publishService.createPreviewVersion("workflow-1", "owner"));

        assertEquals(
            "workflow validation failed: node agent_node agent snapshot contains an unsupported LLM call tool dependency",
            error.getMessage());
        verifyNoInteractions(versionCollection);
    }

    @Test
    void privateSaveRejectsLlmCallToolWithoutPersistingVersionSnapshotsOrProvenance() {
        WorkflowValidationException error = assertThrows(WorkflowValidationException.class,
            () -> publishService.saveVersion("workflow-1", "owner"));

        assertEquals(
            "workflow validation failed: node agent_node agent snapshot contains an unsupported LLM call tool dependency",
            error.getMessage());
        verifyNoInteractions(versionCollection);
    }

    private WorkflowDefinition workflow() {
        var workflow = new WorkflowDefinition();
        workflow.id = "workflow-1";
        workflow.userId = "owner";
        workflow.name = "private workflow";
        workflow.status = WorkflowDefinitionStatus.ACTIVE;
        workflow.draftGraph = """
            {"nodes":[
              {"id":"start","type":"START","config":{}},
              {"id":"agent_node","type":"AGENT","config":{"agent_id":"agent-1"}},
              {"id":"end","type":"END","config":{}}
             ],"edges":[
              {"id":"e1","source":"start","target":"agent_node"},
              {"id":"e2","source":"agent_node","target":"end"}
             ]}
            """;
        return workflow;
    }

    private AgentDefinition agent() {
        var agent = new AgentDefinition();
        agent.id = "agent-1";
        agent.userId = "owner";
        agent.name = "private agent";
        agent.type = DefinitionType.AGENT;
        agent.status = AgentStatus.DRAFT;
        agent.tools = List.of(ToolRef.fromLegacyToolId("llm-call:target"));
        agent.createdAt = ZonedDateTime.parse("2026-08-05T10:00:00Z");
        agent.updatedAt = agent.createdAt;
        return agent;
    }
}
