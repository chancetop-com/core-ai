package ai.core.api.server.skillhub;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * Catalog-level view of a skill (metadata only, no content). What a hub search hit
 * carries; clients resolving a {@code {namespace}/{name}} reference use {@code id} as
 * the stable key.
 *
 * @author stephen
 */
public class SkillHubSummary {
    @Property(name = "id")
    public String id;

    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "namespace")
    public String namespace;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "source_type")
    public String sourceType;

    @Property(name = "digest")
    public String digest;

    @Property(name = "resource_count")
    public Integer resourceCount;

    @Property(name = "score")
    public Integer score;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
