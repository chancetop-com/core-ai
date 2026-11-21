package ai.core.api.server.evaluation.response;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class RunView {
    @Property(name = "id")
    public String id;

    @Property(name = "evaluation_id")
    public String evaluationId;

    @Property(name = "actor_id")
    public String actorId;

    @Property(name = "spec_id")
    public String specId;

    @Property(name = "snapshot_id")
    public String snapshotId;

    @Property(name = "purpose")
    public String purpose;

    @Property(name = "experiment_label")
    public String experimentLabel;

    @Property(name = "status")
    public String status;

    @Property(name = "planned_trials")
    public Integer plannedTrials;

    @Property(name = "started_trials")
    public Integer startedTrials;

    @Property(name = "succeeded_trials")
    public Integer succeededTrials;

    @Property(name = "failed_trials")
    public Integer failedTrials;

    @Property(name = "unmeasured_trials")
    public Integer unmeasuredTrials;

    @Property(name = "cancel_requested_by")
    public String cancelRequestedBy;

    @Property(name = "deadline_at")
    public ZonedDateTime deadlineAt;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "completed_at")
    public ZonedDateTime completedAt;

    @Property(name = "error")
    public String error;

    @Property(name = "request_key")
    public String requestKey;
}
