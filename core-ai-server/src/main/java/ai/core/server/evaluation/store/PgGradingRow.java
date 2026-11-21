package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgGradingRow {
    @Column(name = "id")
    public String id;
    @Column(name = "trial_id")
    public String trialId;
    @Column(name = "grader_suite_id")
    public String graderSuiteId;
    @Column(name = "output_hash")
    public String outputHash;
    @Column(name = "generation")
    public Integer generation;
    @Column(name = "status")
    public String status;
    @Column(name = "results_json")
    public String resultsJson;
    @Column(name = "error")
    public String error;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
    @Column(name = "completed_at")
    public ZonedDateTime completedAt;
}
