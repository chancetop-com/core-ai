package ai.core.server.trigger.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
@Collection(name = "triggers")
public class Trigger {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "name")
    public String name;

    @Field(name = "description")
    public String description;

    @NotNull
    @Field(name = "type")
    public TriggerType type;

    @NotNull
    @Field(name = "enabled")
    public Boolean enabled;

    @Field(name = "config")
    public Map<String, String> config;

    @NotNull
    @Field(name = "action_type")
    public String actionType;

    @Field(name = "action_config")
    public Map<String, String> actionConfig;

    @Field(name = "last_triggered_at")
    public ZonedDateTime lastTriggeredAt;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
