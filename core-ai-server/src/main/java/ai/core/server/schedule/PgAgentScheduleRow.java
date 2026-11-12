package ai.core.server.schedule;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgAgentScheduleRow {
    @Column(name = "id")
    public String id;

    @Column(name = "agent_id")
    public String agentId;

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
