package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author Xander
 */
@Collection(name = "system_prompts")
public class SystemPrompt {
    @Id
    public String id;

    @NotNull
    @Field(name = "prompt_id")
    public String promptId;

    @NotNull
    @Field(name = "name")
    public String name;

    @Field(name = "description")
    public String description;

    @NotNull
    @Field(name = "content")
    public String content;

    @Field(name = "variables")
    public List<String> variables;

    @NotNull
    @Field(name = "version")
    public Integer version;

    @Field(name = "changelog")
    public String changelog;

    @Field(name = "tags")
    public List<String> tags;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
