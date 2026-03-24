package ai.core.api.a2a;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class AgentCard {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "version")
    public String version;

    @Property(name = "capabilities")
    public AgentCapabilities capabilities;

    @Property(name = "skills")
    public List<Skill> skills;

    @Property(name = "defaultInputModes")
    public List<String> defaultInputModes;

    @Property(name = "defaultOutputModes")
    public List<String> defaultOutputModes;

    public static class AgentCapabilities {
        @Property(name = "streaming")
        public Boolean streaming;

        @Property(name = "pushNotifications")
        public Boolean pushNotifications;
    }

    public static class Skill {
        public static Skill of(String name, String description) {
            var skill = new Skill();
            skill.name = name;
            skill.description = description;
            return skill;
        }

        @Property(name = "name")
        public String name;

        @Property(name = "description")
        public String description;
    }
}
