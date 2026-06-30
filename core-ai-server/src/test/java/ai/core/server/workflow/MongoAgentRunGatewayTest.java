package ai.core.server.workflow;

import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.domain.DefinitionType;
import ai.core.server.workflow.engine.WorkflowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
