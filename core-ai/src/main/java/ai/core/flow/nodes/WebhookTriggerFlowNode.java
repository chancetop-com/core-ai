package ai.core.flow.nodes;

import ai.core.flow.FlowEdge;
import ai.core.flow.FlowNode;
import ai.core.flow.FlowNodeResult;
import ai.core.flow.FlowNodeType;
import core.framework.json.JSON;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class WebhookTriggerFlowNode extends FlowNode<WebhookTriggerFlowNode> {
    private String webhookUrl;

    public WebhookTriggerFlowNode() {

    }

    public WebhookTriggerFlowNode(String id, String name, String baseUrl) {
        super(id, name, "Webhook", "Webhook Trigger Node", FlowNodeType.TRIGGER, WebhookTriggerFlowNode.class);
        this.webhookUrl = baseUrl + "/" + id;
    }

    public String getWebhookUrl() {
        return webhookUrl;
    }

    @Override
    public FlowNodeResult execute(String input, Map<String, Object> variables) {
        return null;
    }

    @Override
    public void init(List<FlowNode<?>> settings, List<FlowEdge<?>> edges) {

    }

    @Override
    public void check(List<FlowNode<?>> settings) {

    }

    @Override
    public String serialization(WebhookTriggerFlowNode node) {
        return JSON.toJSON(new Domain().from(this));
    }

    @Override
    public void deserialization(WebhookTriggerFlowNode node, String c) {
        JSON.fromJSON(Domain.class, c).setupNode(node);
    }

    public static class Domain extends FlowNode.Domain<Domain> {
        public String publishUrl;

        @Override
        public Domain from(FlowNode<?> node) {
            this.fromBase(node);
            this.publishUrl = ((WebhookTriggerFlowNode) node).getWebhookUrl();
            return this;
        }

        @Override
        public void setupNode(FlowNode<?> node) {
            super.setupNodeBase(node);
            ((WebhookTriggerFlowNode) node).webhookUrl = this.publishUrl;
        }
    }
}
