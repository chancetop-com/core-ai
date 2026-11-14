package ai.core.server.quality;
import ai.core.server.agent.AgentDefinitionStore;
import ai.core.server.domain.AgentDefinition;

import ai.core.api.server.agent.AgentQualityReportView;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityGateMode;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRunStatus;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.Comparator;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * The publish gate. The candidate is built once; every active rule is evaluated against the same snapshot;
 * the run is persisted before the commit and the caller then performs the conditional agent update. This
 * service never writes the agent document, so a failed gate cannot partially mutate a publish.
 *
 * @author core-ai
 */
public class PublishQualityGateService implements PublishQualityGate {
    private static final Logger LOGGER = LoggerFactory.getLogger(PublishQualityGateService.class);
    private static final Duration RUN_DEADLINE = Duration.ofSeconds(45);
    private static final long STARTED_GRACE_SECONDS = 90;

    @Inject
    PublishCandidateBuilder candidateBuilder;
    @Inject
    QualityPolicyService policyService;
    @Inject
    QualityRuleRegistry registry;
    @Inject
    QualitySnapshotService snapshotService;
    @Inject
    QualityDecisionGateway decisions;
    @Inject
    QualityPublicationService publicationService;
    @Inject
    AgentQualityStore store;
    @Inject
    AgentDefinitionStore agentDefinitionStore;

    private final QualityConcurrencyGuard concurrencyGuard = new QualityConcurrencyGuard();

    @Override
    public PublishCandidate buildCandidate(AgentDefinition entity) {
        return candidateBuilder.build(entity);
    }

    @Override
    public QualityRunResult precheck(AgentDefinition entity, String userId, String idempotencyKey) {
        var candidate = buildCandidate(entity);
        return evaluate(entity, candidate, userId, QualityGateMode.PRECHECK, idempotencyKey, false);
    }

    @Override
    @SuppressWarnings("PMD.UseTryWithResources")
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings({"UTWR_USE_TRY_WITH_RESOURCES", "LEST_LOST_EXCEPTION_STACK_TRACE"})
    public QualityRunResult evaluate(AgentDefinition entity, PublishCandidate candidate, String userId,
                                     QualityGateMode mode, String idempotencyKey, boolean enforce) {
        var policy = policyService.active();
        var operationKeyHash = operationKeyHash(userId, entity.id, idempotencyKey);
        var existing = findExisting(operationKeyHash, candidate);
        if (existing != null) return existing;

        var lease = enforce ? concurrencyGuard.acquire(entity.id, userId) : null;
        try {
            var run = createRun(entity, candidate, userId, policy, mode, operationKeyHash);
            var evaluation = evaluateRules(entity, candidate, policy, run);
            finishRun(run, evaluation);
            var result = resultOf(run, false);
            if (enforce) assertPublishable(result);
            return result;
        } finally {
            if (lease != null) lease.close();
        }
    }

    private QualityRunResult findExisting(String operationKeyHash, PublishCandidate candidate) {
        if (operationKeyHash == null) return null;
        var existing = store.findRunByOperationKey(operationKeyHash).orElse(null);
        if (existing == null) return null;
        if (!equal(candidate.executionCandidateHash(), existing.executionCandidateHash)
            || !equal(candidate.metadataHash(), existing.metadataHash)) {
            throw QualityGateException.idempotencyConflict(existing.id);
        }
        if (existing.runStatus == QualityRunStatus.STARTED && !expired(existing)) {
            throw QualityGateException.inProgress(existing.id);
        }
        if (existing.runStatus == QualityRunStatus.STARTED) {
            store.updateRun(existing, QualityRunStatus.ABORTED);
            throw QualityGateException.inProgress(existing.id);
        }
        return resultOf(existing, publicationService.publicationByOperationKey(operationKeyHash) != null);
    }

    private boolean expired(AgentQualityRun run) {
        if (run.createdAt == null) return false;
        return run.createdAt.plusSeconds(STARTED_GRACE_SECONDS).isBefore(ZonedDateTime.now());
    }

    @SuppressWarnings({"checkstyle:AvoidHidingCauseException", "PMD.PreserveStackTrace"})
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("LEST_LOST_EXCEPTION_STACK_TRACE")
    private AgentQualityRun createRun(AgentDefinition entity, PublishCandidate candidate, String userId,
                                      QualityPolicyService.ActivePolicy policy, QualityGateMode mode,
                                      String operationKeyHash) {
        var run = new AgentQualityRun();
        run.id = UUID.randomUUID().toString();
        run.agentId = entity.id;
        run.actor = userId;
        run.mode = mode;
        run.idempotencyKey = null;
        run.operationKeyHash = operationKeyHash;
        run.definitionRevision = candidate.definitionRevision();
        run.executionCandidateHash = candidate.executionCandidateHash();
        run.metadataHash = candidate.metadataHash();
        run.policyRevision = policy.revision();
        run.runStatus = QualityRunStatus.STARTED;
        run.createdAt = ZonedDateTime.now();
        run.expiresAt = run.createdAt.plusDays(90);
        try {
            store.insertRun(run);
        } catch (RuntimeException e) {
            if (operationKeyHash == null) throw e;
            var existing = store.findRunByOperationKey(operationKeyHash).orElse(null);
            if (existing != null) throw QualityGateException.inProgress(existing.id);
            throw e;
        }
        return run;
    }

    private Evaluation evaluateRules(AgentDefinition entity, PublishCandidate candidate,
                                     QualityPolicyService.ActivePolicy policy, AgentQualityRun run) {
        var budget = QualityBudget.forDeadline(RUN_DEADLINE);
        var snapshot = snapshotService.snapshot(candidate, policy.revision());
        run.dependencyFingerprint = snapshot.dependencyFingerprint();
        var outcomes = new ArrayList<QualityRunAggregator.RuleOutcome>();
        var rules = new ArrayList<QualityPolicyService.ActiveRule>(policy.rules());
        rules.sort(Comparator.comparingInt(rule -> priority(rule.enforcement())));
        String shadowError = null;

        for (var rule : rules) {
            if (rule.enforcement() == QualityEnforcement.SHADOW && budget.exhausted()) {
                shadowError = "SHADOW_BUDGET_EXHAUSTED";
                continue;
            }
            outcomes.add(evaluateRule(snapshot, rule, budget, entity));
        }
        var aggregated = QualityRunAggregator.aggregate(outcomes);
        return new Evaluation(aggregated, shadowError);
    }

    private QualityRunAggregator.RuleOutcome evaluateRule(PublishQualitySnapshot snapshot,
                                                          QualityPolicyService.ActiveRule rule,
                                                          QualityBudget budget, AgentDefinition entity) {
        var holder = new QualityRunAggregator.AgentQualityRuleRevisionHolder(
            rule.revision().ruleId, rule.revision().revision, rule.enforcement());
        QualityRuleEvaluation evaluation;
        try {
            var evaluator = registry.require(rule.revision().evaluatorKey);
            var context = new QualityRuleContext(snapshot, rule.revision(), rule.enforcement(), budget, decisions);
            evaluation = evaluator.evaluate(context);
            for (var issue : evaluation.issues()) {
                if (issue.enforcement == null) issue.enforcement = rule.enforcement();
                if (issue.locationPrecision == null) issue.locationPrecision = "FIELD";
            }
        } catch (RuntimeException e) {
            LOGGER.warn("quality rule failed unexpectedly, rule={}, agent={}: {}",
                rule.revision().ruleId, entity.id, e.getMessage());
            var issue = QualityIssues.of(rule.revision(), rule.enforcement(), QualityIssueStatus.ERROR,
                "/", ai.core.server.quality.domain.QualityEvidenceKind.CODE_FACT, "RULE_EXECUTION_ERROR",
                ai.core.server.quality.domain.QualityRecommendedAction.CONTACT_ADMIN, false,
                java.util.Map.of(), "质量规则执行失败。", "联系规则管理员检查该规则。");
            evaluation = QualityRuleEvaluation.issue(QualityIssueStatus.ERROR, List.of(issue), null,
                "RULE_EXECUTION_ERROR");
        }
        return new QualityRunAggregator.RuleOutcome(holder, evaluation);
    }

    private void finishRun(AgentQualityRun run, Evaluation evaluation) {
        var aggregated = evaluation.aggregated();
        run.gateResult = aggregated.gateResult();
        run.checksComplete = aggregated.checksComplete();
        run.issues = aggregated.issues();
        run.ruleResults = aggregated.ruleResults();
        run.routeFingerprint = aggregated.routeFingerprint();
        run.ruleResultDigest = aggregated.ruleResultDigest();
        run.shadowExecutionError = evaluation.shadowError();
        run.runStatus = QualityRunStatus.COMPLETED;
        run.finishedAt = ZonedDateTime.now();
        if (!store.updateRun(run, QualityRunStatus.STARTED)) {
            throw QualityGateException.unavailable(QualityReportMapper.toView(run, false));
        }
    }

    public void assertPublishable(QualityRunResult result) {
        if (result.passed()) return;
        if (result.run().gateResult == QualityGateResult.REJECTED) {
            throw QualityGateException.rejected(result.report());
        }
        throw QualityGateException.unavailable(result.report());
    }

    @Override
    public void recheck(PublishCandidate candidate, AgentQualityRun run) {
        publicationService.recheck(candidate, run);
    }

    @Override
    public void recordPublication(AgentQualityRun run, PublishCandidate candidate, String userId) {
        publicationService.record(run, candidate, userId);
    }

    public QualityRuleEvaluation testRule(PublishCandidate candidate,
                                          ai.core.server.quality.domain.AgentQualityRuleRevision revision) {
        var snapshot = snapshotService.snapshot(candidate, 0);
        var budget = QualityBudget.forTest();
        try {
            var evaluator = registry.require(revision.evaluatorKey);
            var context = new QualityRuleContext(snapshot, revision, QualityEnforcement.SHADOW, budget, decisions);
            return evaluator.evaluate(context);
        } catch (RuntimeException e) {
            var issue = QualityIssues.of(revision, QualityEnforcement.SHADOW, QualityIssueStatus.ERROR,
                candidate.promptField(), ai.core.server.quality.domain.QualityEvidenceKind.CODE_FACT,
                "RULE_TEST_ERROR", ai.core.server.quality.domain.QualityRecommendedAction.CONTACT_ADMIN,
                false, java.util.Map.of(), "规则试测失败。", "检查规则参数与决策模型配置。");
            return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR, List.of(issue), null, e.getMessage());
        }
    }

    public AgentQualityRun run(String runId) {
        return publicationService.run(runId);
    }

    public AgentQualityRun latestRun(String agentId) {
        return publicationService.latestRun(agentId);
    }

    public AgentQualityReportView report(AgentQualityRun run, boolean includeShadow) {
        return QualityReportMapper.toView(run, includeShadow);
    }

    @Override
    public AgentQualityReportView report(String agentId, String runId) {
        var run = "latest".equals(runId) ? latestRun(agentId) : run(runId);
        if (run == null) throw new core.framework.web.exception.NotFoundException("quality run not found");
        if (!agentId.equals(run.agentId)) throw new core.framework.web.exception.NotFoundException("quality run not found");
        return QualityReportMapper.toView(run, false);
    }

    public QualityRunResult resultOf(AgentQualityRun run, boolean alreadyCommitted) {
        return new QualityRunResult(run, QualityReportMapper.toView(run, false), alreadyCommitted);
    }

    private String operationKeyHash(String userId, String agentId, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank()) return null;
        return QualityHashes.textHash(userId + "|" + agentId + "|" + idempotencyKey);
    }

    private int priority(QualityEnforcement enforcement) {
        return switch (enforcement) {
            case BLOCK -> 0;
            case WARN -> 1;
            case SHADOW -> 2;
        };
    }

    private boolean equal(String left, String right) {
        return left == null ? right == null : left.equals(right);
    }

    private record Evaluation(QualityRunAggregator.Aggregated aggregated, String shadowError) {
    }
}






