package ai.core.server.evaluation.store;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgRunRow {
    @Column(name = "id")
    public String id;
    @Column(name = "evaluation_id")
    public String evaluationId;
    @Column(name = "actor_id")
    public String actorId;
    @Column(name = "request_key")
    public String requestKey;
    @Column(name = "request_hash")
    public String requestHash;
    @Column(name = "spec_id")
    public String specId;
    @Column(name = "snapshot_id")
    public String snapshotId;
    @Column(name = "purpose")
    public String purpose;
    @Column(name = "experiment_label")
    public String experimentLabel;
    @Column(name = "status")
    public String status;
    @Column(name = "planned_trials")
    public Integer plannedTrials;
    @Column(name = "started_trials")
    public Integer startedTrials;
    @Column(name = "succeeded_trials")
    public Integer succeededTrials;
    @Column(name = "failed_trials")
    public Integer failedTrials;
    @Column(name = "unmeasured_trials")
    public Integer unmeasuredTrials;
    @Column(name = "cancel_requested_by")
    public String cancelRequestedBy;
    @Column(name = "cancel_requested_at")
    public ZonedDateTime cancelRequestedAt;
    @Column(name = "deadline_at")
    public ZonedDateTime deadlineAt;
    @Column(name = "report_json")
    public String reportJson;
    @Column(name = "error")
    public String error;
    @Column(name = "created_at")
    public ZonedDateTime createdAt;
    @Column(name = "started_at")
    public ZonedDateTime startedAt;
    @Column(name = "completed_at")
    public ZonedDateTime completedAt;
    @Column(name = "data")
    public String data;
}
