package ai.core.api.server.schedule;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class CreateScheduleRequest {
    @NotNull
    @Property(name = "agent_id")
    public String agentId;

    @NotNull
    @Property(name = "cron_expression")
    public String cronExpression;

    @Property(name = "timezone")
    public String timezone;

    @Property(name = "input")
    public String input;

    @Property(name = "concurrency_policy")
    public String concurrencyPolicy;
}
