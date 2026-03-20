package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
@Collection(name = "skills")
public class SkillDefinition {
    @Id
    public String id;

    @NotNull
    @Field(name = "namespace")
    public String namespace;

    @NotNull
    @Field(name = "name")
    public String name;

    @NotNull
    @Field(name = "qualified_name")
    public String qualifiedName;

    @Field(name = "description")
    public String description;

    @NotNull
    @Field(name = "source_type")
    public SkillSourceType sourceType;

    @NotNull
    @Field(name = "content")
    public String content;

    @Field(name = "allowed_tools")
    public List<String> allowedTools;

    @Field(name = "metadata")
    public Map<String, String> metadata;

    @Field(name = "resources")
    public List<SkillResource> resources;

    @Field(name = "repo_config")
    public SkillRepoConfig repoConfig;

    @Field(name = "version")
    public String version;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
