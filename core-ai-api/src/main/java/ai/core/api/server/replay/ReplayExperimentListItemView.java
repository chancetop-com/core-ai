package ai.core.api.server.replay;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * Lightweight list row for replay experiments (no payloads).
 *
 * @author stephen
 */
public class ReplayExperimentListItemView {
    @Property(name = "id")
    public String id;

    @Property(name = "origin")
    public String origin;

    @Property(name = "span_name")
    public String spanName;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "original_model")
    public String originalModel;

    @Property(name = "run_count")
    public Integer runCount;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
