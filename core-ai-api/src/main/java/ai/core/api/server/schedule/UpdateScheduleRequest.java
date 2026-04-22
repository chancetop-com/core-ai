package ai.core.api.server.schedule;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class UpdateScheduleRequest {
    @Property(name = "cron_expression")
    public String cronExpression;

    @Property(name = "timezone")
    public String timezone;

    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "input")
    public String input;

    @Property(name = "variables")
    public Map<String, String> variables;

    @Property(name = "concurrency_policy")
    public String concurrencyPolicy;
}
