package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class SkillQualityRuleTemplateListResponse {
    @Property(name = "templates")
    public List<SkillQualityRuleTemplateView> templates;

    @Property(name = "input_options")
    public List<String> inputOptions;
}
