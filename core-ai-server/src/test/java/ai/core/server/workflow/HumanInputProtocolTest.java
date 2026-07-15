package ai.core.server.workflow;

import ai.core.server.domain.WorkflowNodeRun;
import ai.core.server.workflow.engine.WorkflowNode;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HumanInputProtocolTest {
    @Test
    void approvalModeRequiresApproveAndRejectsInput() {
        var node = approvalNode();
        assertDoesNotThrow(() -> HumanInputProtocol.validate(node, Boolean.TRUE, null));
        assertDoesNotThrow(() -> HumanInputProtocol.validate(node, Boolean.FALSE, null));
        assertThrows(BadRequestException.class, () -> HumanInputProtocol.validate(node, null, null));
        assertThrows(BadRequestException.class, () -> HumanInputProtocol.validate(node, Boolean.TRUE, "{}"));
    }

    @Test
    void inputModeRejectsApproveAndValidatesSchema() {
        var node = inputNode();
        assertThrows(BadRequestException.class, () -> HumanInputProtocol.validate(node, Boolean.TRUE, "{\"reason\":\"ok\"}"));
        assertThrows(BadRequestException.class, () -> HumanInputProtocol.validate(node, null, null));                       // required missing
        assertThrows(BadRequestException.class, () -> HumanInputProtocol.validate(node, null, "{\"reason\":\"  \"}"));      // required blank
        assertThrows(BadRequestException.class, () -> HumanInputProtocol.validate(node, null, "{\"reason\":\"ok\",\"count\":\"3\"}"));   // wrong type
        assertThrows(BadRequestException.class, () -> HumanInputProtocol.validate(node, null, "not json"));
        assertDoesNotThrow(() -> HumanInputProtocol.validate(node, null, "{\"reason\":\"ok\",\"count\":3}"));
        assertDoesNotThrow(() -> HumanInputProtocol.validate(node, null, "{\"reason\":\"ok\"}"));   // optional field omitted
    }

    @Test
    void describeBuildsContractFromGraphAndAskSnapshot() {
        var waiting = new WorkflowNodeRun();
        waiting.inputJson = "{\"mode\":\"input\",\"prompt\":\"please review\"}";
        var view = HumanInputProtocol.describe(inputNode(), waiting);
        assertEquals("n1", view.nodeId);
        assertEquals("input", view.mode);
        assertEquals("please review", view.prompt);
        assertEquals(2, view.fields.size());
        assertEquals("reason", view.fields.getFirst().name);
        assertTrue(view.fields.getFirst().required);
        assertEquals("number", view.fields.get(1).type);
    }

    @Test
    void describeApprovalModeHasNoFields() {
        var waiting = new WorkflowNodeRun();
        waiting.inputJson = "{\"mode\":\"approval\",\"prompt\":\"approve?\"}";
        var view = HumanInputProtocol.describe(approvalNode(), waiting);
        assertEquals("approval", view.mode);
        assertEquals("approve?", view.prompt);
        assertNull(view.fields);
    }

    @Test
    void describeToleratesMalformedAskSnapshot() {
        var waiting = new WorkflowNodeRun();
        waiting.inputJson = "not json";
        assertEquals("", HumanInputProtocol.describe(approvalNode(), waiting).prompt);
    }

    private WorkflowNode approvalNode() {
        return new WorkflowNode("n1", "HUMAN_INPUT", List.of(),
            Map.of("mode", "approval", "prompt", "approve?", "approve_edge_id", "e1", "reject_edge_id", "e2"));
    }

    private WorkflowNode inputNode() {
        return new WorkflowNode("n1", "HUMAN_INPUT", List.of(), Map.of(
            "mode", "input", "prompt", "please review",
            "fields", List.of(
                Map.of("name", "reason", "type", "text", "label", "Reason", "required", Boolean.TRUE),
                Map.of("name", "count", "type", "number", "required", Boolean.FALSE))));
    }
}
