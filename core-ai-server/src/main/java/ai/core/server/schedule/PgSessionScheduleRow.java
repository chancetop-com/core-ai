package ai.core.server.schedule;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgSessionScheduleRow {
    @Column(name = "id")
    public String id;

    @Column(name = "session_id")
    public String sessionId;

    @Column(name = "user_id")
    public String userId;

    @Column(name = "enabled")
    public Boolean enabled;

    @Column(name = "next_run_at")
    public ZonedDateTime nextRunAt;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "data")
    public String data;
}
