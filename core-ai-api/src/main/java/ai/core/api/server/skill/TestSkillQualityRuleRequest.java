package ai.core.api.server.skill;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author core-ai
 */
public class TestSkillQualityRuleRequest {
    @NotNull
    @Property(name = "skill_id")
    public String skillId;

    @Property(name = "sample_name")
    public String sampleName;

    @Property(name = "skill_md")
    public String skillMd;

    @Property(name = "expected_result")
    public String expectedResult;
}
