package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgEvaluationRow {
    @Column(name = "id")
    public String id;
    @Column(name = "owner_id")
    public String ownerId;
    @Column(name = "agent_id")
    public String agentId;
    @Column(name = "name")
    public String name;
    @Column(name = "description")
    public String description;
    @Column(name = "baseline_run_id")
    public String baselineRunId;
    @Column(name = "baseline_generation")
    public Integer baselineGeneration;
    @Column(name = "archived_at")
    public ZonedDateTime archivedAt;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;
    @Column(name = "data")
    public String data;
}
