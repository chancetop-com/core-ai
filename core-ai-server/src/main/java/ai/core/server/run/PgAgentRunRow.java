package ai.core.server.run;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgAgentRunRow {
    @Column(name = "id")
    public String id;

    @Column(name = "agent_id")
    public String agentId;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "triggered_by")
    public String triggeredBy;

    @Column(name = "status")
    public String status;

    @Column(name = "schedule_id")
    public String scheduleId;

    @Column(name = "trace_id")
    public String traceId;

    @Column(name = "started_at")
    public ZonedDateTime startedAt;

    @Column(name = "queued_at")
    public ZonedDateTime queuedAt;

    @Column(name = "completed_at")
    public ZonedDateTime completedAt;

    @Column(name = "has_artifacts")
    public Boolean hasArtifacts;

    @Column(name = "output")
    public String output;

    @Column(name = "error")
    public String error;

    @Column(name = "data")
    public String data;
}

