package ai.core.api.server.skill;

import core.framework.api.json.Property;

/**
 * @author core-ai
 */
public class SkillSdkCapabilityView {
    @Property(name = "kind")
    public String kind;

    @Property(name = "path")
    public String path;

    @Property(name = "raw")
    public String raw;

    @Property(name = "supported")
    public Boolean supported;
}
