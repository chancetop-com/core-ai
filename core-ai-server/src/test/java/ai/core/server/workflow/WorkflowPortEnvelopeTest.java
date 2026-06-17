package ai.core.server.workflow;

import ai.core.api.server.workflow.ExportWorkflowResponse;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkflowPortEnvelopeTest {
    @Test
    void parseValidEnvelope() {
        String content = """
            {"format": "core-ai-workflow-export/v1", "name": "wf", "mode": "WORKFLOW",
             "graph": "{\\"nodes\\":[],\\"edges\\":[]}"}
            """;
        ExportWorkflowResponse envelope = WorkflowPortService.parseEnvelope(content);
        assertEquals("wf", envelope.name);
        assertEquals("WORKFLOW", envelope.mode);
    }

    @Test
    void rejectWrongFormat() {
        String content = """
            {"format": "something-else", "name": "wf", "graph": "{}"}
            """;
        assertThrows(BadRequestException.class, () -> WorkflowPortService.parseEnvelope(content));
    }

    @Test
    void rejectMissingGraph() {
        String content = """
            {"format": "core-ai-workflow-export/v1", "name": "wf"}
            """;
        assertThrows(BadRequestException.class, () -> WorkflowPortService.parseEnvelope(content));
    }

    @Test
    void rejectInvalidMode() {
        String content = """
            {"format": "core-ai-workflow-export/v1", "name": "wf", "mode": "BOGUS", "graph": "{}"}
            """;
        assertThrows(BadRequestException.class, () -> WorkflowPortService.parseEnvelope(content));
    }
}
