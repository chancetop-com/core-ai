package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class EvaluationView {
    @Property(name = "id")
    public String id;

    @Property(name = "owner_id")
    public String ownerId;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "baseline_run_id")
    public String baselineRunId;

    @Property(name = "baseline_generation")
    public Integer baselineGeneration;

    @Property(name = "archived_at")
    public ZonedDateTime archivedAt;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
