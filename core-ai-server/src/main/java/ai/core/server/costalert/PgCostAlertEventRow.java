package ai.core.server.costalert;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgCostAlertEventRow {
    @Column(name = "id")
    public String id;

    @Column(name = "rule_id")
    public String ruleId;

    @Column(name = "rule_name")
    public String ruleName;

    @Column(name = "date")
    public ZonedDateTime date;

    @Column(name = "scope")
    public String scope;

    @Column(name = "scope_value")
    public String scopeValue;

    @Column(name = "metric")
    public String metric;

    @Column(name = "threshold")
    public Double threshold;

    @Column(name = "actual_value")
    public Double actualValue;

    @Column(name = "status")
    public String status;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "data")
    public String data;
}
