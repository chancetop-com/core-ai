package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@Collection(name = "marketplace_repos")
public class MarketplaceRepo {
    @Id
    public String id;

    @NotNull
    @Field(name = "name")
    public String name;

    @NotNull
    @Field(name = "repo_url")
    public String repoUrl;

    @NotNull
    @Field(name = "branch")
    public String branch;

    @NotNull
    @Field(name = "skill_path")
    public String skillPath;

    @Field(name = "description")
    public String description;

    @Field(name = "icon_url")
    public String iconUrl;

    @Field(name = "skill_count")
    public Integer skillCount;

    @Field(name = "featured")
    public Boolean featured;

    @Field(name = "category")
    public String category;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
