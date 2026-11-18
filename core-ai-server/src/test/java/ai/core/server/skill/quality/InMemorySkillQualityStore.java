package ai.core.server.skill.quality;

import ai.core.server.skill.quality.domain.SkillQualityPolicyHead;
import ai.core.server.skill.quality.domain.SkillQualityPolicyRevision;
import ai.core.server.skill.quality.domain.SkillQualityRule;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * @author core-ai
 */
class InMemorySkillQualityStore implements SkillQualityStore {
    private final Map<String, SkillQualityRule> rules = new LinkedHashMap<>();
    private final Map<String, SkillQualityRuleRevision> revisions = new LinkedHashMap<>();
    private final Map<Integer, SkillQualityPolicyRevision> policies = new LinkedHashMap<>();
    private final Map<String, SkillQualityPolicyHead> heads = new LinkedHashMap<>();

    @Override
    public Optional<SkillQualityRule> getRule(String id) {
        return Optional.ofNullable(rules.get(id));
    }

    @Override
    public Optional<SkillQualityRule> findRuleByKey(String key) {
        return rules.values().stream().filter(rule -> key.equals(rule.key)).findFirst();
    }

    @Override
    public List<SkillQualityRule> listRules() {
        var result = new ArrayList<>(rules.values());
        result.sort((left, right) -> left.key.compareTo(right.key));
        return result;
    }

    @Override
    public void insertRule(SkillQualityRule rule) {
        rules.put(rule.id, rule);
    }

    @Override
    public boolean insertRuleIfAbsent(SkillQualityRule rule) {
        return rules.putIfAbsent(rule.id, rule) == null;
    }

    @Override
    public void replaceRule(SkillQualityRule rule) {
        rules.put(rule.id, rule);
    }

    @Override
    public Optional<SkillQualityRuleRevision> getRevision(String ruleId, int revision) {
        return Optional.ofNullable(revisions.get(ruleId + ":" + revision));
    }

    @Override
    public List<SkillQualityRuleRevision> listRevisions(String ruleId) {
        return revisions.values().stream()
            .filter(revision -> ruleId.equals(revision.ruleId))
            .sorted((left, right) -> Integer.compare(right.revision, left.revision))
            .toList();
    }

    @Override
    public void insertRevision(SkillQualityRuleRevision revision) {
        revisions.put(revision.id, revision);
    }

    @Override
    public boolean insertRevisionIfAbsent(SkillQualityRuleRevision revision) {
        return revisions.putIfAbsent(revision.id, revision) == null;
    }

    @Override
    public void replaceRevision(SkillQualityRuleRevision revision) {
        revisions.put(revision.id, revision);
    }

    @Override
    public Optional<SkillQualityPolicyRevision> getPolicy(int revision) {
        return Optional.ofNullable(policies.get(revision));
    }

    @Override
    public List<SkillQualityPolicyRevision> listPolicies() {
        return policies.values().stream()
            .sorted((left, right) -> Integer.compare(right.revision, left.revision))
            .toList();
    }

    @Override
    public void insertPolicy(SkillQualityPolicyRevision policy) {
        policies.put(policy.revision, policy);
    }

    @Override
    public boolean insertPolicyIfAbsent(SkillQualityPolicyRevision policy) {
        return policies.putIfAbsent(policy.revision, policy) == null;
    }

    @Override
    public Optional<SkillQualityPolicyHead> getHead(String scope) {
        return Optional.ofNullable(heads.get(scope));
    }

    @Override
    public boolean insertHeadIfAbsent(SkillQualityPolicyHead head) {
        return heads.putIfAbsent(head.scope, head) == null;
    }

    @Override
    public boolean replaceHead(SkillQualityPolicyHead head, int expectedGeneration) {
        var current = heads.get(head.scope);
        if (current == null || current.generation != expectedGeneration) return false;
        heads.put(head.scope, head);
        return true;
    }
}
