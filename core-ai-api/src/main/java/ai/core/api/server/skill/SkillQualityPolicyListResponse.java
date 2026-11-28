package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class SkillQualityPolicyListResponse {
    @Property(name = "active")
    public SkillQualityPolicyView active;

    @Property(name = "history")
    public List<SkillQualityPolicyView> history;

    @Property(name = "scopes")
    public List<SkillQualityPolicyView> scopes;
}
