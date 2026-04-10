package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class UpdateSkillRequest {
    @Property(name = "description")
    public String description;

    @Property(name = "content")
    public String content;

    @Property(name = "allowed_tools")
    public List<String> allowedTools;

    @Property(name = "resources")
    public List<SkillResourceRequest> resources;

    public static class SkillResourceRequest {
        @NotNull
        @Property(name = "path")
        public String path;

        @NotNull
        @Property(name = "content")
        public String content;
    }
}
