package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class SkillQualityRuleListResponse {
    @Property(name = "rules")
    public List<SkillQualityRuleView> rules;

    @Property(name = "policy_revision")
    public Integer policyRevision;
}
