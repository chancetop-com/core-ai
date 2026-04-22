package ai.core.api.server.schedule;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentScheduleView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "agent_id")
    public String agentId;

    @NotNull
    @Property(name = "cron_expression")
    public String cronExpression;

    @Property(name = "timezone")
    public String timezone;

    @NotNull
    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "input")
    public String input;

    @Property(name = "variables")
    public Map<String, String> variables;

    @Property(name = "concurrency_policy")
    public String concurrencyPolicy;

    @Property(name = "next_run_at")
    public ZonedDateTime nextRunAt;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
