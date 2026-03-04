package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.Map;

/**
 * @author stephen
 */
@Collection(name = "tool_registry")
public class ToolRegistry {
    @Id
    public String id;

    @NotNull
    @Field(name = "name")
    public String name;

    @Field(name = "description")
    public String description;

    @NotNull
    @Field(name = "type")
    public ToolType type;

    @Field(name = "category")
    public String category;

    @NotNull
    @Field(name = "config")
    public Map<String, String> config;

    @NotNull
    @Field(name = "enabled")
    public Boolean enabled;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
