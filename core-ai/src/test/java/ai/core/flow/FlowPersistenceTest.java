package ai.core.flow;

import ai.core.flow.edges.ConnectionEdge;
import ai.core.flow.nodes.EmptyFlowNode;
import ai.core.flow.nodes.OperatorSwitchFlowNode;
import ai.core.flow.nodes.ThrowErrorFlowNode;
import ai.core.flow.nodes.WebhookTriggerFlowNode;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
class FlowPersistenceTest {
    private Flow setup() {
        var nodeTrigger = new WebhookTriggerFlowNode(UUID.randomUUID().toString(), "Webhook", "https://localhost/webhook");
        var nodeSwitch = new OperatorSwitchFlowNode(UUID.randomUUID().toString(), "Switch");
        var nodeEmpty = new EmptyFlowNode(UUID.randomUUID().toString(), "Empty");
        var nodeError = new ThrowErrorFlowNode(UUID.randomUUID().toString(), "Error", "test switch error");
        var edgeTrigger = new ConnectionEdge(UUID.randomUUID().toString());
        var edgeSwitch1 = new ConnectionEdge(UUID.randomUUID().toString(), "switch 1");
        var edgeSwitch2 = new ConnectionEdge(UUID.randomUUID().toString(), "switch 2");
        edgeTrigger.connect(nodeTrigger.getId(), nodeSwitch.getId());
        edgeSwitch1.connect(nodeSwitch.getId(), nodeEmpty.getId());
        edgeSwitch2.connect(nodeSwitch.getId(), nodeError.getId());
        return Flow.builder()
                .id("test_id")
                .name("test_flow")
                .description("test flow")
                .persistence(new FlowPersistence())
                .persistenceProvider(new TemporaryPersistenceProvider())
                .nodes(List.of(nodeTrigger, nodeSwitch, nodeEmpty, nodeError))
                .edges(List.of(edgeTrigger, edgeSwitch1, edgeSwitch2))
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
        assert flow.getNodes().size() == 4;
        assert flow.getEdges().size() == 3;
    }
}