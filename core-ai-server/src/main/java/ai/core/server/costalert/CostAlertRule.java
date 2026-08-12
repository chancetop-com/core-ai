package ai.core.server.costalert;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Cost alert rule. Evaluated by CostAlertJob against daily trace aggregates.
 *
 * @author stephen
 */
@Collection(name = "cost_alert_rules")
public class CostAlertRule {
    @Id
    public String id;

    @NotNull
    @Field(name = "name")
    public String name;

    @NotNull
    @Field(name = "enabled")
    public Boolean enabled = Boolean.TRUE;

    @NotNull
    @Field(name = "metric")
    public CostAlertMetric metric;

    @NotNull
    @Field(name = "scope")
    public CostAlertScope scope;

    /**
     * user id or agent id for scoped rules; empty string for global scope
     * (kept non-null so the unique event index works without partial filters).
     */
    @NotNull
    @Field(name = "scope_value")
    public String scopeValue = "";

    @NotNull
    @Field(name = "threshold")
    public Double threshold;

    /**
     * JSON array of notification targets:
     * [{"type":"notification","userId":"..."},{"type":"channel","channelId":"...","recipient":"..."}]
     */
    @NotNull
    @Field(name = "targets")
    public String targets;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
