package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class SkillDownloadResponse {
    @NotNull
    @Property(name = "name")
    public String name;

    @NotNull
    @Property(name = "namespace")
    public String namespace;

    @NotNull
    @Property(name = "content")
    public String content;

    @Property(name = "resources")
    public List<SkillResourceView> resources;

    public static class SkillResourceView {
        @NotNull
        @Property(name = "path")
        public String path;

        @NotNull
        @Property(name = "content")
        public String content;
    }
}
