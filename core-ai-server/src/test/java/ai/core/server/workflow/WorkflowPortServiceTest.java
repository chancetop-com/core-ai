package ai.core.server.workflow;

import ai.core.api.server.workflow.ExportWorkflowResponse;
import ai.core.server.domain.WorkflowDefinition;
import core.framework.inject.Inject;
import core.framework.json.JSON;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowPortTestModule.class)
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

    private ExportWorkflowResponse makeEnvelope(String name, String graph) {
        var envelope = new ExportWorkflowResponse();
        envelope.format = WorkflowPortService.EXPORT_FORMAT;
        envelope.name = name;
        envelope.mode = "WORKFLOW";
        envelope.graph = graph;
        return envelope;
    }
}
