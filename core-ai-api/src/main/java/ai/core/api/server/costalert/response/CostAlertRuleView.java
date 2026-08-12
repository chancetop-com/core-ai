package ai.core.api.server.costalert.response;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class CostAlertRuleView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "metric")
    public String metric;

    @Property(name = "scope")
    public String scope;

    @Property(name = "scope_value")
    public String scopeValue;

    @Property(name = "threshold")
    public Double threshold;

    @Property(name = "targets")
    public String targets;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
