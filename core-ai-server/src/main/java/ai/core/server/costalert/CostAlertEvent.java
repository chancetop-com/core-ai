package ai.core.server.costalert;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Fired cost alert event. Unique index (rule_id, scope, scope_value, date)
 * guarantees each rule fires at most once per scope per day.
 *
 * @author stephen
 */
@Collection(name = "cost_alert_events")
public class CostAlertEvent {
    @Id
    public String id;

    @NotNull
    @Field(name = "rule_id")
    public String ruleId;

    @NotNull
    @Field(name = "rule_name")
    public String ruleName;

    @NotNull
    @Field(name = "date")
    public ZonedDateTime date;

    @NotNull
    @Field(name = "scope")
    public String scope;

    @NotNull
    @Field(name = "scope_value")
    public String scopeValue;

    @NotNull
    @Field(name = "metric")
    public String metric;

    @NotNull
    @Field(name = "threshold")
    public Double threshold;

    @NotNull
    @Field(name = "actual_value")
    public Double actualValue;

    @NotNull
    @Field(name = "detail")
    public String detail;

    @NotNull
    @Field(name = "status")
    public String status = "sent";

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
