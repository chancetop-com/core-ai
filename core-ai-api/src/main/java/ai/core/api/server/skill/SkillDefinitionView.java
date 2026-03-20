package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class SkillDefinitionView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "namespace")
    public String namespace;

    @NotNull
    @Property(name = "name")
    public String name;

    @NotNull
    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "description")
    public String description;

    @NotNull
    @Property(name = "source_type")
    public String sourceType;

    @Property(name = "allowed_tools")
    public List<String> allowedTools;

    @Property(name = "metadata")
    public Map<String, String> metadata;

    @Property(name = "version")
    public String version;

    @NotNull
    @Property(name = "user_id")
    public String userId;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
