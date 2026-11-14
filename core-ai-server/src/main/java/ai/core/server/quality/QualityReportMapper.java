package ai.core.server.quality;

import ai.core.api.server.agent.AgentQualityIssueView;
import ai.core.api.server.agent.AgentQualityReportView;
import ai.core.server.quality.domain.AgentQualityRun;
import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.ArrayList;
import java.util.List;

/**
 * Domain run to client report. SHADOW results stay in the stored run (audit) and are not shown to the
 * author; WARN results are shown but do not change the gate result.
 *
 * @author core-ai
 */
public final class QualityReportMapper {
    public static AgentQualityReportView toView(AgentQualityRun run, boolean includeShadow) {
        var view = new AgentQualityReportView();
        view.runId = run.id;
        view.agentId = run.agentId;
        view.definitionRevision = run.definitionRevision;
        view.executionCandidateHash = run.executionCandidateHash;
        view.metadataHash = run.metadataHash;
        view.policyRevision = run.policyRevision;
        view.routeFingerprint = run.routeFingerprint;
        view.gateResult = run.gateResult == null ? QualityGateResult.UNAVAILABLE.name() : run.gateResult.name();
        view.checksComplete = run.checksComplete;
        view.runStatus = run.runStatus == null ? null : run.runStatus.name();
        view.mode = run.mode == null ? null : run.mode.name();
        view.startedAt = run.createdAt;
        view.finishedAt = run.finishedAt;

        var issues = new ArrayList<AgentQualityIssueView>();
        int warn = 0;
        int blockFail = 0;
        int incomplete = 0;
        if (run.issues != null) {
            for (var issue : run.issues) {
                if (!includeShadow && issue.enforcement == QualityEnforcement.SHADOW) continue;
                issues.add(issueView(issue));
                if (issue.enforcement == QualityEnforcement.WARN
                    && (issue.status == QualityIssueStatus.FAIL
                    || issue.status == QualityIssueStatus.INDETERMINATE
                    || issue.status == QualityIssueStatus.ERROR)) {
                    warn++;
                }
                if (issue.enforcement == QualityEnforcement.BLOCK && issue.status == QualityIssueStatus.FAIL) blockFail++;
                if (issue.enforcement == QualityEnforcement.BLOCK
                    && (issue.status == QualityIssueStatus.ERROR
                    || issue.status == QualityIssueStatus.INDETERMINATE)) {
                    incomplete++;
                }
            }
        }
        view.issues = issues;
        view.warnCount = warn;
        view.blockFailCount = blockFail;
        view.incompleteCount = incomplete;
        view.rulesEvaluated = run.ruleResults == null ? 0 : run.ruleResults.size();
        view.rulesApplicable = run.ruleResults == null ? 0 : run.ruleResults.size();
        return view;
    }

    public static AgentQualityIssueView issueView(StoredQualityIssue issue) {
        var view = new AgentQualityIssueView();
        view.ruleId = issue.ruleId;
        view.ruleRevision = issue.ruleRevision;
        view.ruleName = issue.ruleName;
        view.ruleRequirement = issue.ruleRequirement;
        view.status = issue.status == null ? null : issue.status.name();
        view.enforcement = issue.enforcement == null ? null : issue.enforcement.name();
        view.fieldPath = issue.fieldPath;
        view.evidenceKind = issue.evidenceKind == null ? null : issue.evidenceKind.name();
        view.evidenceText = issue.evidenceText;
        view.locationPrecision = issue.locationPrecision;
        view.message = issue.message;
        view.suggestion = issue.suggestion;
        view.decisionType = issue.decisionType;
        view.questionId = issue.questionId;
        view.predicate = issue.predicate;
        view.polarity = issue.polarity;
        view.decisionMetric = issue.decisionMetric;
        view.affirmativeProbability = issue.affirmativeProbability;
        view.affirmativeThreshold = issue.affirmativeThreshold;
        view.negativeThreshold = issue.negativeThreshold;
        view.decisionMapping = issue.decisionMapping;
        view.issueReason = issue.issueReason;
        view.retryable = issue.retryable;
        view.recommendedAction = issue.recommendedAction == null ? null : issue.recommendedAction.name();
        view.actualCount = issue.actualCount;
        view.maxCount = issue.maxCount;
        view.category = issue.category;
        view.resourceType = issue.resourceType;
        view.resourceId = issue.resourceId;
        view.subagentId = issue.subagentId;
        return view;
    }

    public static List<AgentQualityIssueView> issueViews(List<StoredQualityIssue> issues, boolean includeShadow) {
        if (issues == null) return List.of();
        return issues.stream()
            .filter(issue -> includeShadow || issue.enforcement != QualityEnforcement.SHADOW)
            .map(QualityReportMapper::issueView)
            .toList();
    }

    private QualityReportMapper() {
    }
}
