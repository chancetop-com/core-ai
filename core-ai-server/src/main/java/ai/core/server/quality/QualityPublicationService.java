package ai.core.server.quality;
import ai.core.server.agent.AgentDefinitionStore;

import ai.core.server.quality.domain.AgentPublicationRecord;
import ai.core.server.quality.domain.AgentQualityRun;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Pre-commit recheck and durable publication audit. The recheck can prove the agent document and the policy
 * did not change; it cannot make external resources atomic with the agent write, and it does not claim to.
 *
 * @author core-ai
 */
public class QualityPublicationService {
    private static final Logger LOGGER = LoggerFactory.getLogger(QualityPublicationService.class);
    private static final Duration POINT_OF_NO_RETURN = Duration.ofSeconds(2);

    @Inject
    AgentDefinitionStore agentDefinitionStore;
    @Inject
    AgentQualityStore qualityStore;
    @Inject
    QualityPolicyService policyService;
    @Inject
    QualitySnapshotService snapshotService;
    @Inject
    QualityDecisionGateway decisions;

    public void record(AgentQualityRun run, PublishCandidate candidate, String userId) {
        try {
            var record = new AgentPublicationRecord();
            record.id = UUID.randomUUID().toString();
            record.agentId = run.agentId;
            record.definitionRevision = run.definitionRevision;
            record.runId = run.id;
            record.executionCandidateHash = run.executionCandidateHash;
            record.metadataHash = run.metadataHash;
            record.policyRevision = run.policyRevision;
            record.routeFingerprint = run.routeFingerprint;
            record.gateResult = run.gateResult;
            record.ruleResultDigest = run.ruleResultDigest;
            record.warnCount = warnCount(run.issues);
            record.operationKeyHash = run.operationKeyHash;
            record.publishedBy = userId;
            record.publishedAt = ZonedDateTime.now();
            qualityStore.insertPublicationRecord(record);
        } catch (RuntimeException e) {
            // the receipt in the agent document is already the audit of record; a missing append-only copy
            // must be visible but must not turn a completed publish into a failure the client retries
            LOGGER.error("failed to append publication record, agent={}, run={}: {}",
                run.agentId, run.id, e.getMessage());
        }
    }

    public void recheck(PublishCandidate candidate, AgentQualityRun run) {
        var started = System.nanoTime();
        var current = agentDefinitionStore.get(candidate.entity().id).orElse(null);
        if (current == null) throw QualityGateException.stale("Agent 已被删除，发布未执行。");
        int currentRevision = current.definitionRevision == null ? 0 : current.definitionRevision;
        if (currentRevision != candidate.definitionRevision()) {
            throw QualityGateException.stale("Agent 在检查后被修改，请刷新后重新检查。");
        }
        var policy = policyService.active();
        if (run.policyRevision == null || policy.revision() != run.policyRevision) {
            throw QualityGateException.stale("发布质量策略在检查后发生变化，请重新检查。");
        }
        var snapshot = snapshotService.snapshot(candidate, run.policyRevision);
        if (run.dependencyFingerprint != null
            && !run.dependencyFingerprint.equals(snapshot.dependencyFingerprint())) {
            throw QualityGateException.stale("Agent 的依赖资源在检查后发生变化，请重新检查。");
        }
        recheckRoutes(run, policy);
        if (Duration.ofNanos(System.nanoTime() - started).compareTo(POINT_OF_NO_RETURN) > 0) {
            throw QualityGateException.stale("质量检查复核耗时超过提交窗口，请重新检查。");
        }
    }

    private void recheckRoutes(AgentQualityRun run, QualityPolicyService.ActivePolicy policy) {
        if (run.ruleResults == null) return;
        Map<String, AgentQualityRuleRevisionHolder> aliases = policy.rules().stream().collect(
            java.util.stream.Collectors.toMap(rule -> rule.revision().ruleId + "@" + rule.revision().revision,
                rule -> new AgentQualityRuleRevisionHolder(rule.revision().modelAlias)));
        for (var result : run.ruleResults) {
            if (result.routeFingerprint == null || result.routeFingerprint.isBlank()) continue;
            var holder = aliases.get(result.ruleId + "@" + result.ruleRevision);
            if (holder == null) continue;
            var current = decisions.currentRouteFingerprint(holder.modelAlias());
            if (current != null && !current.equals(result.routeFingerprint)) {
                throw QualityGateException.stale("检查模型路由已变化，请重试。");
            }
        }
    }

    private int warnCount(List<ai.core.server.quality.domain.StoredQualityIssue> issues) {
        if (issues == null) return 0;
        return (int) issues.stream()
            .filter(issue -> issue.enforcement == ai.core.server.quality.domain.QualityEnforcement.WARN
                && issue.status != ai.core.server.quality.domain.QualityIssueStatus.PASS
                && issue.status != ai.core.server.quality.domain.QualityIssueStatus.SKIPPED)
            .count();
    }

    public AgentQualityRun latestRun(String agentId) {
        return qualityStore.findLatestRun(agentId).orElse(null);
    }

    public AgentQualityRun run(String runId) {
        return qualityStore.getRun(runId).orElse(null);
    }

    public AgentPublicationRecord publicationByOperationKey(String operationKeyHash) {
        if (operationKeyHash == null) return null;
        return qualityStore.findPublicationByOperationKey(operationKeyHash).orElse(null);
    }

    public List<AgentPublicationRecord> publications(String agentId, int limit) {
        return qualityStore.listPublicationRecords(agentId, limit);
    }

    private record AgentQualityRuleRevisionHolder(String modelAlias) {
    }
}


