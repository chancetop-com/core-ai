package ai.core.server.workflow;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgWorkflowRunRow {
    @Column(name = "id")
    public String id;

    @Column(name = "workflow_id")
    public String workflowId;

    @Column(name = "version_id")
    public String versionId;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "mode")
    public String mode;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "triggered_by")
    public String triggeredBy;

    @Column(name = "preview")
    public Boolean preview;

    @Column(name = "parent_run_id")
    public String parentRunId;

    @Column(name = "status")
    public String status;

    @Column(name = "visibility")
    public String visibility;

    @Column(name = "claimed_by")
    public String claimedBy;

    @Column(name = "lease_until")
    public ZonedDateTime leaseUntil;

    @Column(name = "started_at")
    public ZonedDateTime startedAt;

    @Column(name = "completed_at")
    public ZonedDateTime completedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "output")
    public String output;

    @Column(name = "error")
    public String error;

    @Column(name = "artifacts")
    public String artifacts;

    @Column(name = "data")
    public String data;
}
