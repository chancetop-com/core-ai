package ai.core.server.quality;

import ai.core.server.quality.domain.AgentPublicationRecord;
import ai.core.server.quality.domain.AgentQualityPolicyHead;
import ai.core.server.quality.domain.AgentQualityPolicyRevision;
import ai.core.server.quality.domain.AgentQualityRule;
import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityRunStatus;

import java.util.List;
import java.util.Optional;

/**
 * Persistence of publish policy, runs and publication audit. Both store modes are first-class: the agent
 * store already runs on PostgreSQL in deployed environments and on Mongo locally, and a quality gate that
 * only exists on one of them would be bypassed by switching store mode.
 *
 * @author core-ai
 */
public interface AgentQualityStore {
    Optional<AgentQualityRule> getRule(String id);

    Optional<AgentQualityRule> findRuleByKey(String key);

    List<AgentQualityRule> listRules();

    void insertRule(AgentQualityRule rule);

    boolean insertRuleIfAbsent(AgentQualityRule rule);

    void replaceRule(AgentQualityRule rule);

    Optional<AgentQualityRuleRevision> getRevision(String ruleId, int revision);

    List<AgentQualityRuleRevision> listRevisions(String ruleId);

    void insertRevision(AgentQualityRuleRevision revision);

    boolean insertRevisionIfAbsent(AgentQualityRuleRevision revision);

    Optional<AgentQualityPolicyRevision> getPolicy(int revision);

    List<AgentQualityPolicyRevision> listPolicies();

    void insertPolicy(AgentQualityPolicyRevision policy);

    boolean insertPolicyIfAbsent(AgentQualityPolicyRevision policy);

    Optional<AgentQualityPolicyHead> getHead(String scope);

    boolean insertHeadIfAbsent(AgentQualityPolicyHead head);

    boolean replaceHead(AgentQualityPolicyHead head, int expectedGeneration);

    void insertRun(AgentQualityRun run);

    Optional<AgentQualityRun> getRun(String runId);

    Optional<AgentQualityRun> findRunByOperationKey(String operationKeyHash);

    Optional<AgentQualityRun> findLatestRun(String agentId);

    boolean updateRun(AgentQualityRun run, QualityRunStatus expectedStatus);

    void insertPublicationRecord(AgentPublicationRecord record);

    Optional<AgentPublicationRecord> findPublicationByOperationKey(String operationKeyHash);

    List<AgentPublicationRecord> listPublicationRecords(String agentId, int limit);
}
