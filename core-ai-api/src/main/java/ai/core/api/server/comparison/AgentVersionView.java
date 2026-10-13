package ai.core.api.server.comparison;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * A saved agent configuration ("version") that can be compared as one side of an experiment.
 *
 * @author stephen
 */
public class AgentVersionView {
    @Property(name = "id")
    public String id;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "agent_name")
    public String agentName;

    @Property(name = "label")
    public String label;

    @Property(name = "model")
    public String model;

    @Property(name = "config")
    public ComparisonVariantConfigView config;

    @Property(name = "created_by")
    public String createdBy;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
