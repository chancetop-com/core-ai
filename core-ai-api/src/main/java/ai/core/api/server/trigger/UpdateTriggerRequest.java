package ai.core.api.server.trigger;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class UpdateTriggerRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "config")
    public Map<String, String> config;

    @Property(name = "action_type")
    public String actionType;

    @Property(name = "action_config")
    public Map<String, String> actionConfig;
}
