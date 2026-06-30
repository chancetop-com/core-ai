package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author Xander
 */
public class NodeRunTraceMetadataView {
    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "model")
    public String model;

    @Property(name = "multi_modal_model")
    public String multiModalModel;

    @Property(name = "child_trace_id")
    public String childTraceId;

    @Property(name = "child_status")
    public String childStatus;

    @Property(name = "token_usage")
    public Map<String, Long> tokenUsage;
}
