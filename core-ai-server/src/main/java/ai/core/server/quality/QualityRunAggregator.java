package ai.core.server.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.server.quality.domain.StoredRuleResult;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;

/**
 * §4.4 aggregation. There is no weighted score: one failed BLOCK rule cannot be offset by other passes.
 * The three outcomes are deliberately distinct because "the agent is bad" and "the check could not run"
 * need different user actions.
 *
 * @author core-ai
 */
public final class QualityRunAggregator {
    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("WOC_WRITE_ONLY_COLLECTION_LOCAL")
    public static Aggregated aggregate(List<RuleOutcome> outcomes) {
        var issues = new ArrayList<StoredQualityIssue>();
        var results = new ArrayList<StoredRuleResult>();
        var routes = new LinkedHashSet<String>();
        boolean blockFailed = false;
        boolean blockIncomplete = false;
        boolean warnIncomplete = false;

        for (var outcome : outcomes) {
            var evaluation = outcome.evaluation();
            var enforcement = outcome.rule().enforcement();
            for (var issue : evaluation.issues()) {
                issue.enforcement = enforcement;
                issues.add(issue);
            }
            var result = new StoredRuleResult();
            result.ruleId = outcome.rule().ruleId();
            result.ruleRevision = outcome.rule().revision();
            result.status = evaluation.status();
            result.enforcement = enforcement;
            result.routeFingerprint = evaluation.routeFingerprint();
            result.errorReason = evaluation.errorReason();
            results.add(result);
            if (evaluation.routeFingerprint() != null && !evaluation.routeFingerprint().isBlank()) {
                routes.add(evaluation.routeFingerprint());
            }

            if (enforcement == QualityEnforcement.BLOCK) {
                if (evaluation.status() == QualityIssueStatus.FAIL) blockFailed = true;
                if (evaluation.status() == QualityIssueStatus.ERROR
                    || evaluation.status() == QualityIssueStatus.INDETERMINATE
                    || evaluation.status() == QualityIssueStatus.SKIPPED) {
                    blockIncomplete = true;
                }
            } else if (enforcement == QualityEnforcement.WARN && evaluation.status() == QualityIssueStatus.ERROR) {
                warnIncomplete = true;
            }
        }

        var gate = blockFailed ? QualityGateResult.REJECTED
            : blockIncomplete ? QualityGateResult.UNAVAILABLE : QualityGateResult.PASSED;
        return new Aggregated(gate, !blockIncomplete && !warnIncomplete, issues, results,
            routes.isEmpty() ? null : String.join(",", routes), QualityHashes.issueDigest(issues));
    }

    private QualityRunAggregator() {
    }

    public record RuleOutcome(AgentQualityRuleRevisionHolder rule, QualityRuleEvaluation evaluation) {
    }

    public record AgentQualityRuleRevisionHolder(String ruleId, int revision, QualityEnforcement enforcement) {
    }

    public record Aggregated(QualityGateResult gateResult, boolean checksComplete,
                             List<StoredQualityIssue> issues, List<StoredRuleResult> ruleResults,
                             String routeFingerprint, String ruleResultDigest) {
    }
}
