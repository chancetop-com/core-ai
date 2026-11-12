package ai.core.server.task;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgBackgroundTaskRow {
    @Column(name = "id")
    public String id;

    @Column(name = "type")
    public String type;

    @Column(name = "status")
    public String status;

    @Column(name = "status_text")
    public String statusText;

    @Column(name = "claimed_by")
    public String claimedBy;

    @Column(name = "started_at")
    public ZonedDateTime startedAt;

    @Column(name = "completed_at")
    public ZonedDateTime completedAt;

    @Column(name = "retry_count")
    public Integer retryCount;

    @Column(name = "data")
    public String data;
}
