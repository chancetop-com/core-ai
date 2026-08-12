package ai.core.api.server.costalert.response;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class CostAlertEventView {
    @Property(name = "id")
    public String id;

    @Property(name = "rule_id")
    public String ruleId;

    @Property(name = "rule_name")
    public String ruleName;

    @Property(name = "date")
    public ZonedDateTime date;

    @Property(name = "scope")
    public String scope;

    @Property(name = "scope_value")
    public String scopeValue;

    @Property(name = "metric")
    public String metric;

    @Property(name = "threshold")
    public Double threshold;

    @Property(name = "actual_value")
    public Double actualValue;

    @Property(name = "detail")
    public String detail;

    @Property(name = "status")
    public String status;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
