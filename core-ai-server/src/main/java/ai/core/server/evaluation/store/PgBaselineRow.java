package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgBaselineRow {
    @Column(name = "id")
    public String id;
    @Column(name = "evaluation_id")
    public String evaluationId;
    @Column(name = "baseline_run_id")
    public String baselineRunId;
    @Column(name = "previous_run_id")
    public String previousRunId;
    @Column(name = "generation")
    public Integer generation;
    @Column(name = "reason")
    public String reason;
    @Column(name = "actor_id")
    public String actorId;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
}
