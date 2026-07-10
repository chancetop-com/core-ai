package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
public class MarketplaceRepoView {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "repo_url")
    public String repoUrl;

    @Property(name = "description")
    public String description;

    @Property(name = "icon_url")
    public String iconUrl;

    @Property(name = "skill_count")
    public Integer skillCount;

    @Property(name = "featured")
    public Boolean featured;

    @Property(name = "category")
    public String category;

    @Property(name = "installed")
    public Boolean installed;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
