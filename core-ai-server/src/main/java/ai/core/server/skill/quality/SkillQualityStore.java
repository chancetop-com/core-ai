package ai.core.server.skill.quality;

import ai.core.server.skill.quality.domain.SkillQualityPolicyHead;
import ai.core.server.skill.quality.domain.SkillQualityPolicyRevision;
import ai.core.server.skill.quality.domain.SkillQualityRule;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;

import java.util.List;
import java.util.Optional;

/**
 * Persistence for the skill rule catalog and policy. Both store modes are first-class: a gate that only
 * exists in one deployment mode would be bypassed by switching stores.
 *
 * @author core-ai
 */
public interface SkillQualityStore {
    Optional<SkillQualityRule> getRule(String id);

    Optional<SkillQualityRule> findRuleByKey(String key);

    List<SkillQualityRule> listRules();

    void insertRule(SkillQualityRule rule);

    boolean insertRuleIfAbsent(SkillQualityRule rule);

    void replaceRule(SkillQualityRule rule);

    Optional<SkillQualityRuleRevision> getRevision(String ruleId, int revision);

    List<SkillQualityRuleRevision> listRevisions(String ruleId);

    void insertRevision(SkillQualityRuleRevision revision);

    boolean insertRevisionIfAbsent(SkillQualityRuleRevision revision);

    void replaceRevision(SkillQualityRuleRevision revision);

    Optional<SkillQualityPolicyRevision> getPolicy(int revision);

    List<SkillQualityPolicyRevision> listPolicies();

    void insertPolicy(SkillQualityPolicyRevision policy);

    boolean insertPolicyIfAbsent(SkillQualityPolicyRevision policy);

    Optional<SkillQualityPolicyHead> getHead(String scope);

    boolean insertHeadIfAbsent(SkillQualityPolicyHead head);

    boolean replaceHead(SkillQualityPolicyHead head, int expectedGeneration);
}
