package ai.core.api.server.skill;

import ai.core.api.tool.function.CoreAiParameter;
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
        @CoreAiParameter(name = "path", description = "Relative file path under the skill directory.")
        @Property(name = "path")
        public String path;

        @CoreAiParameter(name = "keep", required = false, description = "Set true to keep the stored file at this path, including binary files.")
        @Property(name = "keep")
        public Boolean keep;

        @CoreAiParameter(name = "content", required = false, description = "Text to write for a new or replaced file. Required unless keep is true.")
        @Property(name = "content")
        public String content;
    }
}
