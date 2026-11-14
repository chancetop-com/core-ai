package ai.core.server.quality;

import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.List;
import java.util.Map;

/**
 * Shared mapping of one typed decision answer to a rule verdict. Polarity is explicit: for every question
 * a high value means "the predicate is true", so a FAIL must say which predicate value produced it.
 *
 * @author core-ai
 */
public final class DecisionSupport {
    public static final String AFFIRMATIVE_MEANS_REQUIRED_INTENT = "AFFIRMATIVE_MEANS_REQUIRED_INTENT";
    public static final String AFFIRMATIVE_MEANS_COMPLIANT = "AFFIRMATIVE_MEANS_COMPLIANT";
    public static final String AFFIRMATIVE_PROBABILITY = "affirmative_probability";

    public static Verdict verdict(Double probability, double upper, double lower) {
        if (probability == null) return Verdict.UNCERTAIN;
        if (probability >= upper) return Verdict.AFFIRMATIVE;
        if (probability <= lower) return Verdict.NEGATIVE;
        return Verdict.UNCERTAIN;
    }

    public static void attach(StoredQualityIssue issue, DecisionProof proof) {
        issue.decisionType = proof.decisionType();
        issue.questionId = proof.questionId();
        issue.predicate = proof.predicate();
        issue.polarity = proof.polarity();
        issue.decisionMetric = AFFIRMATIVE_PROBABILITY;
        issue.affirmativeProbability = proof.probability();
        issue.affirmativeThreshold = proof.upper();
        issue.negativeThreshold = proof.lower();
        issue.decisionMapping = proof.mapping();
    }

    public static StoredQualityIssue errorIssue(QualityRuleContext context, QualityDecisionException e,
                                                String fieldPath, String fallbackMessage) {
        var reason = switch (e.kind()) {
            case ROUTE_DRIFT -> "MODEL_ROUTE_DRIFT";
            case BUDGET -> "BUDGET_EXHAUSTED";
            case PROTOCOL -> "DECISION_PROTOCOL_ERROR";
            case PROVIDER -> "DECISION_PROVIDER_ERROR";
        };
        var action = e.kind() == QualityDecisionException.Kind.ROUTE_DRIFT
            || e.kind() == QualityDecisionException.Kind.PROVIDER
            ? QualityRecommendedAction.RETRY : QualityRecommendedAction.CONTACT_ADMIN;
        return QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.ERROR,
            fieldPath, QualityEvidenceKind.SEMANTIC_ASSESSMENT, reason, action, e.retryable(),
            Map.of(), fallbackMessage + "（" + e.getMessage() + "）",
            e.kind() == QualityDecisionException.Kind.ROUTE_DRIFT
                ? "检查模型路由已变化，请重试或联系规则管理员。"
                : "检查未能完成；可重试，若持续失败请联系规则管理员。");
    }

    public static List<StoredQualityIssue> single(StoredQualityIssue issue) {
        return List.of(issue);
    }

    public static DecisionProof requiredIntent(String questionId, String predicate, Double probability,
                                               QualityRuleContext context, String mapping) {
        return new DecisionProof("noul", questionId, predicate, AFFIRMATIVE_MEANS_REQUIRED_INTENT,
            probability, context.upper(), context.lower(), mapping);
    }

    public static DecisionProof compliant(String questionId, String predicate, Double probability,
                                          QualityRuleContext context, String mapping) {
        return new DecisionProof("noul", questionId, predicate, AFFIRMATIVE_MEANS_COMPLIANT,
            probability, context.upper(), context.lower(), mapping);
    }

    private DecisionSupport() {
    }

    public record DecisionProof(String decisionType, String questionId, String predicate, String polarity,
                                Double probability, double upper, double lower, String mapping) {
    }

    public enum Verdict {
        AFFIRMATIVE,
        NEGATIVE,
        UNCERTAIN
    }
}
