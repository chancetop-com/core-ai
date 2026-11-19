package ai.core.server.skill.quality;

import ai.core.decision.DecisionAnswer;
import ai.core.decision.DecisionQuestion;
import ai.core.server.quality.DecisionSupport;
import ai.core.server.quality.QualityDecisionException;
import ai.core.server.quality.QualityRuleEvaluation;
import ai.core.server.quality.QualityRuleEvaluator;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.server.skill.quality.domain.SkillQualityRuleRevision;
import ai.core.utils.JsonUtil;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Admin-composed DECISION rule for skills. The input allowlist, question and criteria are versioned data;
 * there is no expression language and no generated rationale.
 *
 * @author core-ai
 */
public class SkillDecisionQualityRule implements SkillQualityRuleEvaluator {
    public static final String EVALUATOR_KEY = "SKILL.DECISION.CUSTOM";
    private static final int MAX_SECTION_CHARS = 20_000;

    public static QualityRuleEvaluator.RuleTemplate template() {
        return new QualityRuleEvaluator.RuleTemplate(EVALUATOR_KEY, "Custom decision rule", "DECISION", "CUSTOM",
            "An admin-composed semantic rule over the skill package; no server release required.",
            List.of(new QualityRuleEvaluator.RuleParameterSpec("passLabels", "STRING", false,
                    "Choice labels treated as passing, comma separated.", null, List.of()),
                new QualityRuleEvaluator.RuleParameterSpec("minScore", "DECIMAL", false,
                    "Minimum passing score for score rules.", "0.5", List.of())),
            SkillQualityRuleRegistry.inputOptions(), true, List.of());
    }

    @Override
    public String evaluatorKey() {
        return EVALUATOR_KEY;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public QualityRuleEvaluation evaluate(SkillQualityRuleContext context) {
        if (context.decisions() == null) {
            return error(context, "QUALITY_DECISION_UNAVAILABLE", "The decision model is not available.",
                "Contact a rule administrator to check model routing.");
        }
        var rule = context.rule();
        if (rule.decisionQuestion == null || rule.decisionQuestion.isBlank()) {
            return error(context, "RULE_QUESTION_MISSING", "This custom rule has no decision question.",
                "Edit the rule and add a decision question.");
        }
        try {
            return decisionEvaluation(context);
        } catch (InputTooLarge e) {
            var issue = SkillQualityIssues.fromRevision(rule, context.enforcement(), QualityIssueStatus.INDETERMINATE,
                primaryField(rule), QualityEvidenceKind.CODE_FACT, "SECTION_TOO_LARGE",
                QualityRecommendedAction.EDIT_INPUT, false,
                "Selected input is " + e.chars + " characters; the limit is " + MAX_SECTION_CHARS + ".",
                "Shorten " + e.field + " before publishing, or remove it from the rule inputs.");
            return QualityRuleEvaluation.issue(QualityIssueStatus.INDETERMINATE, List.of(issue), null,
                "SECTION_TOO_LARGE");
        } catch (QualityDecisionException e) {
            return decisionError(context, e);
        }
    }

    private QualityRuleEvaluation decisionEvaluation(SkillQualityRuleContext context) {
        var rule = context.rule();
        var decision = context.decisions().decide(rule.modelAlias, state(context), List.of(question(rule)),
            rule.expectedRouteFingerprint, context.budget());
        var answer = decision.require(rule.decisionQuestion);
        var outcome = outcome(context, answer);
        var route = decision.route().routeFingerprint();
        if (outcome.pass()) return QualityRuleEvaluation.pass(route);
        var issue = SkillQualityIssues.fromRevision(rule, context.enforcement(),
            outcome.uncertain() ? QualityIssueStatus.INDETERMINATE : QualityIssueStatus.FAIL,
            primaryField(rule), QualityEvidenceKind.SEMANTIC_ASSESSMENT,
            outcome.uncertain() ? "DECISION_UNCERTAIN" : "DECISION_FAILED",
            QualityRecommendedAction.EDIT_INPUT, false,
            outcome.uncertain() ? "The check could not determine whether this rule is met."
                : "The skill does not satisfy this custom rule.",
            "Edit the skill so it meets the stated requirement.");
        attach(issue, context, outcome);
        return QualityRuleEvaluation.issue(issue.status, List.of(issue), route, null);
    }

    private QualityRuleEvaluation decisionError(SkillQualityRuleContext context, QualityDecisionException e) {
        var reason = switch (e.kind()) {
            case ROUTE_DRIFT -> "MODEL_ROUTE_DRIFT";
            case BUDGET -> "BUDGET_EXHAUSTED";
            case PROTOCOL -> "DECISION_PROTOCOL_ERROR";
            case PROVIDER -> "DECISION_PROVIDER_ERROR";
        };
        var action = e.kind() == QualityDecisionException.Kind.ROUTE_DRIFT
            || e.kind() == QualityDecisionException.Kind.PROVIDER
            ? QualityRecommendedAction.RETRY : QualityRecommendedAction.CONTACT_ADMIN;
        var issue = SkillQualityIssues.fromRevision(context.rule(), context.enforcement(),
            QualityIssueStatus.ERROR, primaryField(context.rule()), QualityEvidenceKind.SEMANTIC_ASSESSMENT,
            reason, action, e.retryable(), "The custom rule could not run.", e.getMessage());
        return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR, List.of(issue), null, reason);
    }

    private DecisionQuestion question(SkillQualityRuleRevision rule) {
        var type = rule.decisionType == null ? "noul" : rule.decisionType.toLowerCase(Locale.ROOT);
        var criteria = rule.decisionCriteria == null
            ? Map.of("yes", "The skill meets this requirement", "no", "The skill does not meet this requirement")
            : rule.decisionCriteria;
        return switch (type) {
            case "choice" -> DecisionQuestion.choice(rule.decisionQuestion, rule.decisionInstruction, criteria);
            case "score" -> DecisionQuestion.score(rule.decisionQuestion, rule.decisionInstruction,
                rule.decisionLevels == null || rule.decisionLevels.isEmpty()
                    ? List.of("low", "medium", "high") : rule.decisionLevels);
            default -> DecisionQuestion.noul(rule.decisionQuestion, rule.decisionInstruction, criteria);
        };
    }

    private Outcome outcome(SkillQualityRuleContext context, DecisionAnswer answer) {
        var rule = context.rule();
        var type = rule.decisionType == null ? "noul" : rule.decisionType.toLowerCase(Locale.ROOT);
        if ("choice".equals(type)) {
            var passLabels = parseLabels(rule.parameters == null ? null : rule.parameters.get("passLabels"));
            var winner = answer.choice();
            var probability = answer.probability();
            if (winner == null) return new Outcome(false, true, probability, "missing choice answer");
            var pass = passLabels.isEmpty() ? !"no".equalsIgnoreCase(winner) : passLabels.contains(winner);
            if (probability == null || probability < context.upper()) {
                return new Outcome(false, true, probability, "winner probability is below the pass threshold");
            }
            return new Outcome(pass, false, probability,
                "choice label " + winner + (pass ? " is treated as compliant" : " is treated as non-compliant"));
        }
        if ("score".equals(type)) {
            var score = answer.score();
            var min = parseDouble(rule.parameters == null ? null : rule.parameters.get("minScore"), 0.5);
            var pass = score != null && score >= min;
            return new Outcome(pass, false, score, "score >= " + min + " is treated as compliant");
        }
        var probability = answer.probability();
        var verdict = DecisionSupport.verdict(probability, context.upper(), context.lower());
        return switch (verdict) {
            case AFFIRMATIVE -> new Outcome(true, false, probability, "affirmative answer is treated as compliant");
            case NEGATIVE -> new Outcome(false, false, probability, "negative answer is treated as non-compliant");
            case UNCERTAIN -> new Outcome(false, true, probability, "probability is inside the uncertain band");
        };
    }

    private void attach(StoredQualityIssue issue, SkillQualityRuleContext context, Outcome outcome) {
        var rule = context.rule();
        issue.decisionType = rule.decisionType;
        issue.questionId = rule.decisionQuestion;
        issue.predicate = rule.decisionQuestion;
        issue.polarity = rule.decisionPolarity;
        issue.decisionMetric = DecisionSupport.AFFIRMATIVE_PROBABILITY;
        issue.affirmativeProbability = outcome.probability();
        issue.affirmativeThreshold = context.upper();
        issue.negativeThreshold = context.lower();
        issue.decisionMapping = outcome.mapping();
    }

    private String state(SkillQualityRuleContext context) {
        var rule = context.rule();
        var candidate = context.candidate();
        var selector = rule.inputSelector == null || rule.inputSelector.isEmpty()
            ? List.of(SkillCustomRuleInput.DESCRIPTION) : rule.inputSelector;
        var state = new LinkedHashMap<String, Object>();
        state.put("task", "skill_publish_quality_custom_rule");
        state.put("rule", rule.ruleId + "@" + rule.revision);
        state.put("ruleRequirement", rule.description);
        if (selector.contains(SkillCustomRuleInput.DESCRIPTION)) {
            state.put("description", candidate.description);
        }
        if (selector.contains(SkillCustomRuleInput.SKILL_CONTENT)) {
            state.put("skillContent", section("SKILL.md", candidate.content));
        }
        if (selector.contains(SkillCustomRuleInput.METADATA)) {
            state.put("name", candidate.name);
            state.put("version", candidate.version);
            state.put("metadata", candidate.metadata);
        }
        if (selector.contains(SkillCustomRuleInput.ALLOWED_TOOLS)) {
            state.put("allowedTools", candidate.allowedTools);
        }
        if (selector.contains(SkillCustomRuleInput.RESOURCE_MANIFEST)) {
            var manifest = new ArrayList<Map<String, Object>>();
            for (var entry : candidate.resources.entrySet()) {
                var item = new LinkedHashMap<String, Object>();
                item.put("path", entry.getKey());
                item.put("bytes", entry.getValue() == null ? 0 : entry.getValue().length);
                manifest.add(item);
            }
            state.put("resources", manifest);
        }
        if (selector.contains(SkillCustomRuleInput.EVAL_DATASET)) {
            var dataset = candidate.resource("eval/eval.json");
            state.put("evalDataset", dataset == null ? null
                : section("eval/eval.json", new String(dataset, StandardCharsets.UTF_8)));
        }
        return JsonUtil.toJson(state);
    }

    private String section(String field, String value) {
        if (value == null) return null;
        if (value.length() > MAX_SECTION_CHARS) throw new InputTooLarge(field, value.length());
        return value;
    }

    private String primaryField(SkillQualityRuleRevision rule) {
        var selector = rule.inputSelector == null ? List.<String>of() : rule.inputSelector;
        if (selector.contains(SkillCustomRuleInput.DESCRIPTION)) return "/description";
        if (selector.contains(SkillCustomRuleInput.SKILL_CONTENT)) return "/SKILL.md";
        if (selector.contains(SkillCustomRuleInput.METADATA)) return "/metadata";
        if (selector.contains(SkillCustomRuleInput.ALLOWED_TOOLS)) return "/allowed-tools";
        if (selector.contains(SkillCustomRuleInput.EVAL_DATASET)) return "/eval/eval.json";
        return "/";
    }

    private QualityRuleEvaluation error(SkillQualityRuleContext context, String reason, String message,
                                        String suggestion) {
        var issue = SkillQualityIssues.fromRevision(context.rule(), context.enforcement(), QualityIssueStatus.ERROR,
            primaryField(context.rule()), QualityEvidenceKind.SEMANTIC_ASSESSMENT, reason,
            QualityRecommendedAction.CONTACT_ADMIN, false, message, suggestion);
        return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR, List.of(issue), null, reason);
    }

    private List<String> parseLabels(String value) {
        if (value == null || value.isBlank()) return List.of();
        var labels = new ArrayList<String>();
        for (var label : value.split(",")) {
            if (!label.isBlank()) labels.add(label.trim());
        }
        return labels;
    }

    private double parseDouble(String value, double fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private record Outcome(boolean pass, boolean uncertain, Double probability, String mapping) {
    }

    private static final class InputTooLarge extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final String field;
        private final int chars;

        private InputTooLarge(String field, int chars) {
            this.field = field;
            this.chars = chars;
        }
    }
}
