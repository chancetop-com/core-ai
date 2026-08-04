package ai.core.flow;

import ai.core.flow.edges.ConnectionEdge;
import ai.core.flow.edges.SettingEdge;
import ai.core.flow.nodes.AgentFlowNode;
import ai.core.flow.nodes.DeepSeekFlowNode;
import ai.core.flow.nodes.EmptyFlowNode;
import ai.core.flow.nodes.OperatorSwitchFlowNode;
import ai.core.flow.nodes.ThrowErrorFlowNode;
import ai.core.flow.nodes.WebhookTriggerFlowNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author stephen
 */
class FlowPersistenceTest {
    private Flow setup() {
        var nodeTrigger = new WebhookTriggerFlowNode(UUID.randomUUID().toString(), "Webhook", "https://localhost/webhook");
        var nodeAgent = new AgentFlowNode(UUID.randomUUID().toString(), "Agent");
        var nodeSwitch = new OperatorSwitchFlowNode(UUID.randomUUID().toString(), "Switch");
        var nodeEmpty = new EmptyFlowNode(UUID.randomUUID().toString(), "Empty");
        var nodeError = new ThrowErrorFlowNode(UUID.randomUUID().toString(), "Error", "test throw error node");
        var edgeTrigger = new ConnectionEdge(UUID.randomUUID().toString()).connect(nodeTrigger.getId(), nodeAgent.getId());
        var edgeAgent = new ConnectionEdge(UUID.randomUUID().toString()).connect(nodeAgent.getId(), nodeSwitch.getId());
        var edgeSwitch1 = new ConnectionEdge(UUID.randomUUID().toString(), "1").connect(nodeSwitch.getId(), nodeError.getId());
        var edgeSwitch2 = new ConnectionEdge(UUID.randomUUID().toString(), "2").connect(nodeSwitch.getId(), nodeEmpty.getId());
        var nodeDeepSeek = new DeepSeekFlowNode(UUID.randomUUID().toString(), "DeepSeek");
        var edgeDeepSeek = new SettingEdge(UUID.randomUUID().toString()).connect(nodeAgent.getId(), nodeDeepSeek.getId());
        nodeAgent.setSystemPrompt("""
                Your are a calculator, you can do any math calculation, only return the result.
                """);
        return Flow.builder()
                .id("test_id")
                .name("test_flow")
                .description("test flow")
                .nodes(List.of(nodeTrigger, nodeAgent, nodeSwitch, nodeEmpty, nodeError, nodeDeepSeek))
                .edges(List.of(edgeTrigger, edgeAgent, edgeSwitch1, edgeSwitch2, edgeDeepSeek))
                .build();
    }

    @Test
    void serialization() {
        var flow = setup();
        var payload = flow.serialization();
        assertFalse(payload.isEmpty());
    }

    @Test
    void deserialize() {
        var flowOriginal = setup();
        var payload = flowOriginal.serialization();
        var flow = new Flow();
        flow.setPersistence(new FlowPersistence());
        flow.deserialization(payload);
        assertEquals(6, flow.getNodes().size());
        assertEquals(5, flow.getEdges().size());
    }

    @Test
    void testEmptyFlow() {
        var flow = new Flow();
        var text = flow.serialization();
        assertNotNull(text);
    }
}