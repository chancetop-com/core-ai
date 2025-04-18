package ai.core.flow;

import ai.core.flow.edges.ConnectionEdge;
import ai.core.flow.edges.SettingEdge;
import ai.core.flow.nodes.AgentFlowNode;
import ai.core.flow.nodes.DeepSeekFlowNode;
import ai.core.flow.nodes.EmptyFlowNode;
import ai.core.flow.nodes.OperatorSwitchFlowNode;
import ai.core.flow.nodes.ThrowErrorFlowNode;
import ai.core.flow.nodes.WebhookTriggerFlowNode;
import ai.core.llm.LLMProviders;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
class FlowPersistenceTest {
    private Flow setup() {
        var llmProviders = new LLMProviders();
        var nodeTrigger = new WebhookTriggerFlowNode(UUID.randomUUID().toString(), "Webhook", "https://localhost/webhook");
        var nodeAgent = new AgentFlowNode(UUID.randomUUID().toString(), "Agent");
        var nodeSwitch = new OperatorSwitchFlowNode(UUID.randomUUID().toString(), "Switch");
        var nodeEmpty = new EmptyFlowNode(UUID.randomUUID().toString(), "Empty");
        var nodeError = new ThrowErrorFlowNode(UUID.randomUUID().toString(), "Error", "test throw error node");
        var edgeTrigger = new ConnectionEdge(UUID.randomUUID().toString());
        var edgeAgent = new ConnectionEdge(UUID.randomUUID().toString());
        var edgeSwitch1 = new ConnectionEdge(UUID.randomUUID().toString(), "1");
        var edgeSwitch2 = new ConnectionEdge(UUID.randomUUID().toString(), "2");
        edgeTrigger.connect(nodeTrigger.getId(), nodeAgent.getId());
        edgeAgent.connect(nodeAgent.getId(), nodeSwitch.getId());
        edgeSwitch1.connect(nodeSwitch.getId(), nodeError.getId());
        edgeSwitch2.connect(nodeSwitch.getId(), nodeEmpty.getId());
        var nodeDeepSeek = new DeepSeekFlowNode(UUID.randomUUID().toString(), "DeepSeek", llmProviders);
        var edgeDeepSeek = new SettingEdge(UUID.randomUUID().toString());
        edgeDeepSeek.connect(nodeAgent.getId(), nodeDeepSeek.getId());
        nodeAgent.setSystemPrompt("""
                Your are a calculator, you can do any math calculation, only return the result.
                """);
        return Flow.builder()
                .id("test_id")
                .name("test_flow")
                .description("test flow")
                .persistence(new FlowPersistence())
                .persistenceProvider(new TemporaryPersistenceProvider())
                .nodes(List.of(nodeTrigger, nodeAgent, nodeSwitch, nodeEmpty, nodeError, nodeDeepSeek))
                .edges(List.of(edgeTrigger, edgeAgent, edgeSwitch1, edgeSwitch2, edgeDeepSeek))
                .build();
    }

    @Test
    void serialization() {
        var flow = setup();
        var payload = flow.serialization();
        assert !payload.isEmpty();
    }

    @Test
    void deserialize() {
        var flowOriginal = setup();
        var payload = flowOriginal.serialization();
        var flow = new Flow();
        flow.setPersistence(new FlowPersistence());
        flow.deserialization(payload);
        assert flow.getNodes().size() == 6;
        assert flow.getEdges().size() == 5;
    }
}