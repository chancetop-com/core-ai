package ai.core.api.server.costalert.request;

import core.framework.api.json.Property;

/**
 * Create or update payload for a cost alert rule.
 * targets is a JSON array text: [{"type":"notification","userId":"..."},{"type":"channel","channelId":"...","recipient":"..."}]
 *
 * @author stephen
 */
public class SaveCostAlertRuleRequest {
    @Property(name = "name")
    public String name;

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

    @Property(name = "enabled")
    public Boolean enabled;
}
