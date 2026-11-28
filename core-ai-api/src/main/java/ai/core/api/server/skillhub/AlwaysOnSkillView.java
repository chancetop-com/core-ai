package ai.core.api.server.skillhub;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Summary of an ALWAYS_ON platform baseline skill exposed via GET /api/skill-hub/always-on.
 *
 * @author stephen
 */
public class AlwaysOnSkillView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "description")
    public String description;

    @Property(name = "digest")
    public String digest;
}
