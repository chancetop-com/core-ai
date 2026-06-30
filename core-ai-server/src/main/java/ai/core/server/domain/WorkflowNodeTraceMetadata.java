package ai.core.server.domain;

import core.framework.mongo.Field;

/**
 * Lightweight execution metadata shown in workflow node traces. Keep this small: detailed transcripts stay on the
 * child run/trace and are linked by id.
 *
 * @author Xander
 */
public class WorkflowNodeTraceMetadata {
    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "agent_name")
    public String agentName;

    @Field(name = "model")
    public String model;

    @Field(name = "multi_modal_model")
    public String multiModalModel;

    @Field(name = "child_trace_id")
    public String childTraceId;

    @Field(name = "child_status")
    public String childStatus;

    @Field(name = "token_usage")
    public TokenUsage tokenUsage;
}
