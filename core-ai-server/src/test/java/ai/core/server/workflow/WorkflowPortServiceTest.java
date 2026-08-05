package ai.core.server.workflow;

import ai.core.api.server.workflow.ExportWorkflowResponse;
import ai.core.server.agent.AgentNameKey;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.domain.WorkflowDefinition;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.mongo.MongoCollection;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.ZonedDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class WorkflowPortServiceTest {
    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    WorkflowDefinitionService definitionService;

    @Inject
    WorkflowPortService portService;

    @Inject
    MongoCollection<AgentDefinition> agentCollection;

    @Test
    void exportProducesEnvelopeFromDraft() {
        String graph = """
            {"nodes": [{"id": "start", "type": "START"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "end"}]}
            """;
        WorkflowDefinition definition = definitionService.create("port-export", "WORKFLOW", graph, "user-1");

        ExportWorkflowResponse envelope = portService.export(definition.id, "user-1");

        assertEquals(WorkflowPortService.EXPORT_FORMAT, envelope.format);
        assertEquals("port-export", envelope.name);
        assertEquals("WORKFLOW", envelope.mode);
        assertEquals(graph, envelope.graph);
    }

    @Test
    void importRoundTripCreatesEquivalentDraft() {
        String graph = """
            {"nodes": [{"id": "start", "type": "START"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "end"}]}
            """;
        WorkflowDefinition source = definitionService.create("rt-source", "WORKFLOW", graph, "user-1");
        ExportWorkflowResponse envelope = portService.export(source.id, "user-1");
        String file = JSON.toJSON(envelope);

        WorkflowPortService.WorkflowImportResult result = portService.importWorkflow(file, null, "user-2");

        assertEquals("rt-source", result.definition().name);
        assertEquals("user-2", result.definition().userId);
        assertEquals(graph, result.definition().draftGraph);
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    void importReportsUnknownAgentReference() {
        String graph = """
            {"nodes": [{"id": "start", "type": "START"},
                       {"id": "a1", "type": "AGENT", "config": {"agent_id": "does-not-exist"}},
                       {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "a1"},
                       {"id": "e1", "source": "a1", "target": "end"}]}
            """;
        String file = JSON.toJSON(makeEnvelope("imp-agent", graph));

        WorkflowPortService.WorkflowImportResult result = portService.importWorkflow(file, null, "user-1");

        assertEquals(1, result.unresolved().size());
        assertEquals("a1", result.unresolved().get(0).nodeId());
        assertEquals("AGENT", result.unresolved().get(0).refType());
    }

    @Test
    void importToleratesNodeWithoutType() {
        String graph = """
            {"nodes": [{"id": "start", "type": "START"},
                       {"id": "mystery"},
                       {"id": "end", "type": "END"}],
             "edges": []}
            """;
        String file = JSON.toJSON(makeEnvelope("typeless", graph));

        WorkflowPortService.WorkflowImportResult result = portService.importWorkflow(file, null, "user-1");

        assertNotNull(result.definition().id);
        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    void importPersistsDescription() {
        String graph = """
            {"nodes": [{"id": "start", "type": "START"}, {"id": "end", "type": "END"}],
             "edges": [{"id": "e0", "source": "start", "target": "end"}]}
            """;
        ExportWorkflowResponse envelope = makeEnvelope("with-desc", graph);
        envelope.description = "a portable workflow";
        String file = JSON.toJSON(envelope);

        WorkflowPortService.WorkflowImportResult result = portService.importWorkflow(file, null, "user-1");

        assertEquals("a portable workflow", result.definition().description);
    }

    @Test
    void importResolvesCallerDraftAndOtherUsersUsablePublishedAgent() {
        AgentDefinition ownDraft = insertAgent("import-own-draft", "user-1", AgentStatus.DRAFT, null);
        var publishedConfig = new AgentPublishedConfig();
        publishedConfig.systemPrompt = "public config";
        AgentDefinition sharedPublished = insertAgent("import-shared", "user-2", AgentStatus.PUBLISHED, publishedConfig);
        String graph = agentGraph("a1", ownDraft.id, "a2", sharedPublished.id);

        WorkflowPortService.WorkflowImportResult result = portService.importWorkflow(
            JSON.toJSON(makeEnvelope("resolvable-agents", graph)), null, "user-1");

        assertTrue(result.unresolved().isEmpty());
    }

    @Test
    void importUsesSecretFreeReplacementWarningForInaccessibleDraftAgent() {
        AgentDefinition privateAgent = insertAgent("import-private", "other-user", AgentStatus.DRAFT, null);
        privateAgent.systemPrompt = "private prompt value";
        agentCollection.replace(privateAgent);
        String graph = agentGraph("n1", privateAgent.id);

        WorkflowPortService.WorkflowImportResult result = portService.importWorkflow(
            JSON.toJSON(makeEnvelope("private-agent", graph)), null, "user-1");

        assertEquals(1, result.unresolved().size());
        assertEquals("Private embedded agent is not available — choose a replacement", result.unresolved().getFirst().message());
        assertFalse(result.unresolved().getFirst().message().contains("private prompt value"));
    }

    @Test
    void importUsesExactGenericReplacementWarningForAgentAndLlmTypeMismatches() {
        AgentDefinition llm = insertAgent("import-llm", "user-1", AgentStatus.DRAFT, null);
        llm.type = DefinitionType.LLM_CALL;
        agentCollection.replace(llm);
        AgentDefinition agent = insertAgent("import-agent", "user-1", AgentStatus.DRAFT, null);
        String graph = "{\"nodes\":["
            + "{\"id\":\"agent-node\",\"type\":\"AGENT\",\"config\":{\"agent_id\":\"" + llm.id + "\"}},"
            + "{\"id\":\"llm-node\",\"type\":\"LLM\",\"config\":{\"agent_id\":\"" + agent.id + "\"}}"
            + "],\"edges\":[]}";

        WorkflowPortService.WorkflowImportResult result = portService.importWorkflow(
            JSON.toJSON(makeEnvelope("wrong-types", graph)), null, "user-1");

        assertEquals(2, result.unresolved().size());
        assertEquals("agent-node", result.unresolved().get(0).nodeId());
        assertEquals(WorkflowPortService.PRIVATE_AGENT_REPLACEMENT_MESSAGE,
            result.unresolved().get(0).message());
        assertEquals("llm-node", result.unresolved().get(1).nodeId());
        assertEquals(WorkflowPortService.PRIVATE_AGENT_REPLACEMENT_MESSAGE,
            result.unresolved().get(1).message());
    }

    private AgentDefinition insertAgent(String namePrefix, String userId, AgentStatus status,
                                        AgentPublishedConfig publishedConfig) {
        var agent = new AgentDefinition();
        agent.id = namePrefix + "-" + UUID.randomUUID();
        agent.userId = userId;
        agent.name = namePrefix;
        agent.nameKey = AgentNameKey.normalize(agent.name);
        agent.type = DefinitionType.AGENT;
        agent.status = status;
        agent.publishedConfig = publishedConfig;
        agent.createdAt = ZonedDateTime.now();
        agent.updatedAt = agent.createdAt;
        agentCollection.insert(agent);
        return agent;
    }

    private String agentGraph(String nodeId, String agentId) {
        return agentGraph(nodeId, agentId, null, null);
    }

    private String agentGraph(String firstNodeId, String firstAgentId, String secondNodeId, String secondAgentId) {
        String secondNode = secondNodeId == null ? "" : ", {\"id\":\"" + secondNodeId
            + "\",\"type\":\"AGENT\",\"config\":{\"agent_id\":\"" + secondAgentId + "\"}}";
        return "{\"nodes\":[{\"id\":\"" + firstNodeId
            + "\",\"type\":\"AGENT\",\"config\":{\"agent_id\":\"" + firstAgentId + "\"}}"
            + secondNode + "],\"edges\":[]}";
    }

    private ExportWorkflowResponse makeEnvelope(String name, String graph) {
        var envelope = new ExportWorkflowResponse();
        envelope.format = WorkflowPortService.EXPORT_FORMAT;
        envelope.name = name;
        envelope.mode = "WORKFLOW";
        envelope.graph = graph;
        return envelope;
    }
}
