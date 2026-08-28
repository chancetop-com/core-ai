package ai.core.api.server.schedule;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * Session-bound scheduled task (session_schedules). Unlike an agent schedule, the
 * task fires by injecting its input into the originating session instead of
 * starting a new agent run.
 *
 * @author stephen
 */
public class SessionScheduleView {
    @Property(name = "id")
    public String id;

    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "user_id")
    public String userId;

    @Property(name = "name")
    public String name;

    @Property(name = "cron_expression")
    public String cronExpression;

    @Property(name = "timezone")
    public String timezone;

    @Property(name = "input")
    public String input;

    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "next_run_at")
    public ZonedDateTime nextRunAt;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
