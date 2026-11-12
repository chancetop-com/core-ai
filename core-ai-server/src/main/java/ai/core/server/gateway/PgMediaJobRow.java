package ai.core.server.gateway;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgMediaJobRow {
    @Column(name = "id")
    public String id;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "agent_run_id")
    public String agentRunId;

    @Column(name = "state")
    public String state;

    @Column(name = "media_type")
    public String mediaType;

    @Column(name = "cost_source")
    public String costSource;

    @Column(name = "file_id")
    public String fileId;

    @Column(name = "artifact_state")
    public String artifactState;

    @Column(name = "artifact_attempts")
    public Integer artifactAttempts;

    @Column(name = "artifact_claimed_at")
    public ZonedDateTime artifactClaimedAt;

    @Column(name = "artifact_next_attempt_at")
    public ZonedDateTime artifactNextAttemptAt;

    @Column(name = "poster_file_id")
    public String posterFileId;

    @Column(name = "poster_attempted_at")
    public ZonedDateTime posterAttemptedAt;

    @Column(name = "artifact_stored_at")
    public ZonedDateTime artifactStoredAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "completed_at")
    public ZonedDateTime completedAt;

    @Column(name = "data")
    public String data;
}
