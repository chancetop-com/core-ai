package ai.core.api.server.skillhub;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Full skill view served by the hub detail endpoint: catalog fields plus SKILL.md
 * content and the resource manifest (paths and fingerprints, no resource content).
 *
 * @author stephen
 */
public class SkillHubDetail {
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

    @Property(name = "allowed_tools")
    public List<String> allowedTools;

    @Property(name = "metadata")
    public Map<String, String> metadata;

    @Property(name = "content")
    public String content;

    @Property(name = "resources")
    public List<SkillHubResourceRef> resources;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
