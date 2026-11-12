package ai.core.server.costalert;

import core.framework.db.Column;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class PgCostAlertRuleRow {
    @Column(name = "id")
    public String id;

    @Column(name = "name")
    public String name;

    @Column(name = "enabled")
    public Boolean enabled;

    @Column(name = "metric")
    public String metric;

    @Column(name = "scope")
    public String scope;

    @Column(name = "scope_value")
    public String scopeValue;

    @Column(name = "threshold")
    public Double threshold;

    @Column(name = "created_at")
    public ZonedDateTime createdAt;

    @Column(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Column(name = "data")
    public String data;
}
