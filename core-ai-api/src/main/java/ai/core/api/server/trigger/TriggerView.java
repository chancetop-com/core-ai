package ai.core.api.server.trigger;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
public class TriggerView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @NotNull
    @Property(name = "type")
    public String type;

    @NotNull
    @Property(name = "enabled")
    public Boolean enabled;

    @Property(name = "config")
    public Map<String, String> config;

    @Property(name = "webhook_url")
    public String webhookUrl;

    @Property(name = "action_type")
    public String actionType;

    @Property(name = "action_config")
    public Map<String, String> actionConfig;

    @Property(name = "last_triggered_at")
    public ZonedDateTime lastTriggeredAt;

    @NotNull
    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
