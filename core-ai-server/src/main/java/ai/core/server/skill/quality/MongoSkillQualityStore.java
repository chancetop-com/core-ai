package ai.core.server.skill.quality;

import ai.core.server.skill.quality.domain.SkillQualityPolicyHead;
import ai.core.server.skill.quality.domain.SkillQualityPolicyRevision;
import ai.core.server.skill.quality.domain.SkillQualityRule;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author core-ai
 */
public class MongoSkillQualityStore implements SkillQualityStore {
    @Inject
    MongoCollection<SkillQualityRule> ruleCollection;
    @Inject
    MongoCollection<SkillQualityRuleRevision> revisionCollection;
    @Inject
    MongoCollection<SkillQualityPolicyRevision> policyCollection;
    @Inject
    MongoCollection<SkillQualityPolicyHead> headCollection;

    @Override
    public Optional<SkillQualityRule> getRule(String id) {
        return ruleCollection.get(id);
    }

    @Override
    public Optional<SkillQualityRule> findRuleByKey(String key) {
        return ruleCollection.findOne(Filters.eq("rule_key", key));
    }

    @Override
    public List<SkillQualityRule> listRules() {
        var query = new Query();
        query.sort = Sorts.ascending("rule_key");
        return ruleCollection.find(query);
    }

    @Override
    public void insertRule(SkillQualityRule rule) {
        ruleCollection.insert(rule);
    }

    @Override
    public boolean insertRuleIfAbsent(SkillQualityRule rule) {
        return insertIfAbsent(ruleCollection, rule);
    }

    @Override
    public void replaceRule(SkillQualityRule rule) {
        ruleCollection.replace(rule);
    }

    @Override
    public Optional<SkillQualityRuleRevision> getRevision(String ruleId, int revision) {
        return revisionCollection.findOne(Filters.and(Filters.eq("rule_id", ruleId), Filters.eq("revision", revision)));
    }

    @Override
    public List<SkillQualityRuleRevision> listRevisions(String ruleId) {
        var query = new Query();
        query.filter = Filters.eq("rule_id", ruleId);
        query.sort = Sorts.descending("revision");
        return revisionCollection.find(query);
    }

    @Override
    public void insertRevision(SkillQualityRuleRevision revision) {
        revisionCollection.insert(revision);
    }

    @Override
    public boolean insertRevisionIfAbsent(SkillQualityRuleRevision revision) {
        return insertIfAbsent(revisionCollection, revision);
    }

    @Override
    public void replaceRevision(SkillQualityRuleRevision revision) {
        revisionCollection.replace(revision);
    }

    @Override
    public Optional<SkillQualityPolicyRevision> getPolicy(int revision) {
        return policyCollection.findOne(Filters.eq("revision", revision));
    }

    @Override
    public List<SkillQualityPolicyRevision> listPolicies() {
        var query = new Query();
        query.sort = Sorts.descending("revision");
        return policyCollection.find(query);
    }

    @Override
    public void insertPolicy(SkillQualityPolicyRevision policy) {
        policyCollection.insert(policy);
    }

    @Override
    public boolean insertPolicyIfAbsent(SkillQualityPolicyRevision policy) {
        return insertIfAbsent(policyCollection, policy);
    }

    @Override
    public Optional<SkillQualityPolicyHead> getHead(String scope) {
        return headCollection.get(scope);
    }

    @Override
    public boolean insertHeadIfAbsent(SkillQualityPolicyHead head) {
        return insertIfAbsent(headCollection, head);
    }

    @Override
    public boolean replaceHead(SkillQualityPolicyHead head, int expectedGeneration) {
        return headCollection.update(
            Filters.and(Filters.eq("_id", head.id), Filters.eq("generation", expectedGeneration)),
            Updates.combine(
                Updates.set("active_revision", head.activeRevision),
                Updates.set("generation", head.generation),
                Updates.set("updated_by", head.updatedBy),
                Updates.set("updated_at", head.updatedAt))) > 0;
    }

    private <T> boolean insertIfAbsent(MongoCollection<T> collection, T entity) {
        try {
            collection.insert(entity);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }
}
