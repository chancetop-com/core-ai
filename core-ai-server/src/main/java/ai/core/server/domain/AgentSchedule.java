package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

import org.bson.types.ObjectId;

/**
 * @author stephen
 */
@Collection(name = "agent_schedules")
public class AgentSchedule {
    @Id
    public ObjectId id;

    @NotNull
    @Field(name = "agent_id")
    public ObjectId agentId;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "cron_expression")
    public String cronExpression;

    @Field(name = "timezone")
    public String timezone;

    @NotNull
    @Field(name = "enabled")
    public Boolean enabled;

    @Field(name = "input")
    public String input;

    @NotNull
    @Field(name = "concurrency_policy")
    public ConcurrencyPolicy concurrencyPolicy;

    @Field(name = "next_run_at")
    public ZonedDateTime nextRunAt;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
