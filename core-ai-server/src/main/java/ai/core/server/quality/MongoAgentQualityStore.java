package ai.core.server.quality;

import ai.core.server.quality.domain.AgentPublicationRecord;
import ai.core.server.quality.domain.AgentQualityPolicyHead;
import ai.core.server.quality.domain.AgentQualityPolicyRevision;
import ai.core.server.quality.domain.AgentQualityRule;
import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityRunStatus;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;
import java.util.Optional;

/**
 * @author core-ai
 */
public class MongoAgentQualityStore implements AgentQualityStore {
    @Inject
    MongoCollection<AgentQualityRule> ruleCollection;
    @Inject
    MongoCollection<AgentQualityRuleRevision> revisionCollection;
    @Inject
    MongoCollection<AgentQualityPolicyRevision> policyCollection;
    @Inject
    MongoCollection<AgentQualityPolicyHead> headCollection;
    @Inject
    MongoCollection<AgentQualityRun> runCollection;
    @Inject
    MongoCollection<AgentPublicationRecord> publicationCollection;

    @Override
    public Optional<AgentQualityRule> getRule(String id) {
        return ruleCollection.get(id);
    }

    @Override
    public Optional<AgentQualityRule> findRuleByKey(String key) {
        return ruleCollection.findOne(Filters.eq("rule_key", key));
    }

    @Override
    public List<AgentQualityRule> listRules() {
        var query = new Query();
        query.sort = Sorts.ascending("rule_key");
        return ruleCollection.find(query);
    }

    @Override
    public void insertRule(AgentQualityRule rule) {
        ruleCollection.insert(rule);
    }

    @Override
    public boolean insertRuleIfAbsent(AgentQualityRule rule) {
        return insertIfAbsent(ruleCollection, rule);
    }

    @Override
    public void replaceRule(AgentQualityRule rule) {
        ruleCollection.replace(rule);
    }

    @Override
    public Optional<AgentQualityRuleRevision> getRevision(String ruleId, int revision) {
        return revisionCollection.findOne(Filters.and(Filters.eq("rule_id", ruleId), Filters.eq("revision", revision)));
    }

    @Override
    public List<AgentQualityRuleRevision> listRevisions(String ruleId) {
        var query = new Query();
        query.filter = Filters.eq("rule_id", ruleId);
        query.sort = Sorts.descending("revision");
        return revisionCollection.find(query);
    }

    @Override
    public void insertRevision(AgentQualityRuleRevision revision) {
        revisionCollection.insert(revision);
    }

    @Override
    public boolean insertRevisionIfAbsent(AgentQualityRuleRevision revision) {
        return insertIfAbsent(revisionCollection, revision);
    }

    @Override
    public Optional<AgentQualityPolicyRevision> getPolicy(int revision) {
        return policyCollection.findOne(Filters.eq("revision", revision));
    }

    @Override
    public List<AgentQualityPolicyRevision> listPolicies() {
        var query = new Query();
        query.sort = Sorts.descending("revision");
        return policyCollection.find(query);
    }

    @Override
    public void insertPolicy(AgentQualityPolicyRevision policy) {
        policyCollection.insert(policy);
    }

    @Override
    public boolean insertPolicyIfAbsent(AgentQualityPolicyRevision policy) {
        return insertIfAbsent(policyCollection, policy);
    }

    @Override
    public Optional<AgentQualityPolicyHead> getHead(String scope) {
        return headCollection.get(scope);
    }

    @Override
    public boolean insertHeadIfAbsent(AgentQualityPolicyHead head) {
        try {
            headCollection.insert(head);
            return true;
        } catch (com.mongodb.MongoWriteException e) {
            if (e.getCode() == 11000) return false;
            throw e;
        }
    }

    @Override
    public boolean replaceHead(AgentQualityPolicyHead head, int expectedGeneration) {
        return headCollection.update(
            Filters.and(Filters.eq("_id", head.id), Filters.eq("generation", expectedGeneration)),
            com.mongodb.client.model.Updates.combine(
                com.mongodb.client.model.Updates.set("active_revision", head.activeRevision),
                com.mongodb.client.model.Updates.set("generation", head.generation),
                com.mongodb.client.model.Updates.set("updated_by", head.updatedBy),
                com.mongodb.client.model.Updates.set("updated_at", head.updatedAt))) > 0;
    }

    @Override
    public void insertRun(AgentQualityRun run) {
        runCollection.insert(run);
    }

    @Override
    public Optional<AgentQualityRun> getRun(String runId) {
        return runCollection.get(runId);
    }

    @Override
    public Optional<AgentQualityRun> findRunByOperationKey(String operationKeyHash) {
        return runCollection.findOne(Filters.eq("operation_key_hash", operationKeyHash));
    }

    @Override
    public Optional<AgentQualityRun> findLatestRun(String agentId) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.sort = Sorts.descending("created_at");
        query.limit = 1;
        return runCollection.find(query).stream().findFirst();
    }

    @Override
    public boolean updateRun(AgentQualityRun run, QualityRunStatus expectedStatus) {
        // claim the status transition with a filtered scalar update, then persist the full document; the
        // full document contains nested issue lists whose $set encoding is not guaranteed by the codec
        var claimed = runCollection.update(
            Filters.and(Filters.eq("_id", run.id), Filters.eq("run_status", expectedStatus.name())),
            com.mongodb.client.model.Updates.set("run_status", run.runStatus.name()));
        if (claimed == 0) return false;
        runCollection.replace(run);
        return true;
    }

    @Override
    public void insertPublicationRecord(AgentPublicationRecord record) {
        publicationCollection.insert(record);
    }

    @Override
    public Optional<AgentPublicationRecord> findPublicationByOperationKey(String operationKeyHash) {
        return publicationCollection.findOne(Filters.eq("operation_key_hash", operationKeyHash));
    }

    @Override
    public List<AgentPublicationRecord> listPublicationRecords(String agentId, int limit) {
        var query = new Query();
        query.filter = Filters.eq("agent_id", agentId);
        query.sort = Sorts.descending("published_at");
        query.limit = limit;
        return publicationCollection.find(query);
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
