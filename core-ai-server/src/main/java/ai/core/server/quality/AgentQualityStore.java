package ai.core.server.quality;

import ai.core.server.quality.domain.AgentPublicationRecord;
import ai.core.server.quality.domain.AgentQualityPolicyHead;
import ai.core.server.quality.domain.AgentQualityPolicyRevision;
import ai.core.server.quality.domain.AgentQualityRule;
import ai.core.server.quality.domain.AgentQualityRuleRevision;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityRunStatus;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

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

    void replaceRevision(AgentQualityRuleRevision revision);

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

    /**
     * Latest completed author-side run for the agent. The default implementation is correct on any
     * store; PostgreSQL overrides it with one DISTINCT ON query for list pages.
     */
    default Optional<AgentQualityRun> findLatestCompletedRun(String agentId, Set<QualityGateMode> modes) {
        if (modes == null || modes.isEmpty()) return Optional.empty();
        if (agentId == null) return Optional.empty();
        return listRuns(agentId, 50).stream()
            .filter(run -> run.runStatus == QualityRunStatus.COMPLETED
                && run.mode != null && modes.contains(run.mode))
            .findFirst();
    }

    default Map<String, AgentQualityRun> findLatestCompletedRuns(Collection<String> agentIds,
                                                                 Set<QualityGateMode> modes) {
        if (modes == null || modes.isEmpty()) return Map.of();
        if (agentIds == null || agentIds.isEmpty()) return Map.of();
        var result = new HashMap<String, AgentQualityRun>();
        for (var agentId : agentIds) {
            findLatestCompletedRun(agentId, modes).ifPresent(run -> result.put(agentId, run));
        }
        return result;
    }

    List<AgentQualityRun> listRuns(String agentId, int limit);

    boolean updateRun(AgentQualityRun run, QualityRunStatus expectedStatus);

    void insertPublicationRecord(AgentPublicationRecord record);

    Optional<AgentPublicationRecord> findPublicationByOperationKey(String operationKeyHash);

    List<AgentPublicationRecord> listPublicationRecords(String agentId, int limit);
}
