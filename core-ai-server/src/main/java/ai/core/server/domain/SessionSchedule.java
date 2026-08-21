package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Session-bound scheduled task. When the cron expression fires, the task's input is
 * injected into the originating session so the agent continues executing there —
 * unlike {@link AgentSchedule}, which starts a new agent run.
 *
 * @author stephen
 */
@Collection(name = "session_schedules")
public class SessionSchedule {
    @Id
    public String id;

    @NotNull
    @Field(name = "session_id")
    public String sessionId;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @Field(name = "name")
    public String name;

    @NotNull
    @Field(name = "cron_expression")
    public String cronExpression;

    @NotNull
    @Field(name = "timezone")
    public String timezone;

    @Field(name = "input")
    public String input;

    @NotNull
    @Field(name = "enabled")
    public Boolean enabled;

    @NotNull
    @Field(name = "next_run_at")
    public ZonedDateTime nextRunAt;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
