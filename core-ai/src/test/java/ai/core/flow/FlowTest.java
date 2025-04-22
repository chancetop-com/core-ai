package ai.core.flow;

import ai.core.flow.edges.ConnectionEdge;
import ai.core.flow.nodes.EmptyFlowNode;
import ai.core.flow.nodes.OperatorSwitchFlowNode;
import ai.core.flow.nodes.ThrowErrorFlowNode;
import ai.core.flow.nodes.WebhookTriggerFlowNode;
import ai.core.persistence.providers.TemporaryPersistenceProvider;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
class FlowTest {
    private Flow setup() {
        var nodeTrigger = new WebhookTriggerFlowNode(UUID.randomUUID().toString(), "Webhook", "https://localhost/webhook");
        var nodeSwitch = new OperatorSwitchFlowNode(UUID.randomUUID().toString(), "Switch");
        var nodeEmpty = new EmptyFlowNode(UUID.randomUUID().toString(), "Empty");
        var nodeError = new ThrowErrorFlowNode(UUID.randomUUID().toString(), "Error", "test switch error");
        var edgeTrigger = new ConnectionEdge(UUID.randomUUID().toString()).connect(nodeTrigger.getId(), nodeSwitch.getId());
        var edgeSwitch1 = new ConnectionEdge(UUID.randomUUID().toString(), "switch 1").connect(nodeSwitch.getId(), nodeEmpty.getId());
        var edgeSwitch2 = new ConnectionEdge(UUID.randomUUID().toString(), "switch 2").connect(nodeSwitch.getId(), nodeError.getId());
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
    void test() {
        var flow = setup();
        Assertions.assertThrows(RuntimeException.class, () -> flow.execute(flow.getNodeByName("Switch").getId(), "switch 2", null));
    }
}