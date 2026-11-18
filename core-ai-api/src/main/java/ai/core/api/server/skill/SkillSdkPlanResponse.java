package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class SkillSdkPlanResponse {
    @Property(name = "plan_digest")
    public String planDigest;

    @Property(name = "skills")
    public List<SkillPlan> skills;

    public static class SkillPlan {
        @Property(name = "skill_id")
        public String skillId;

        @Property(name = "qualified_name")
        public String qualifiedName;

        @Property(name = "content_digest")
        public String contentDigest;

        @Property(name = "sdk_capabilities_version")
        public String sdkCapabilitiesVersion;

        @Property(name = "capabilities")
        public List<SkillSdkCapabilityView> capabilities;
    }
}
