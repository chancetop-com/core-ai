package ai.core.server.quality;

import ai.core.decision.DecisionQuestion;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Admin-composed DECISION rule. The input allowlist, question text and criteria are versioned data; no
 * expression language and no generated rationale.
 *
 * @author core-ai
 */
public class DecisionQualityRule implements QualityRuleEvaluator {
    public static final String EVALUATOR_KEY = "DECISION.CUSTOM";

    @Override
    public QualityRuleEvaluation evaluate(QualityRuleContext context) {
        if (context.decisions() == null) {
            return error(context, "DECISION_UNAVAILABLE", "自定义语义规则无法执行：决策模型不可用。");
        }
        var rule = context.rule();
        if (rule.decisionQuestion == null || rule.decisionQuestion.isBlank()) {
            return error(context, "RULE_QUESTION_MISSING", "自定义语义规则缺少决策问题。");
        }
        var question = question(rule);
        try {
            var decision = context.decisions().decide(rule.modelAlias, state(context),
                List.of(question), rule.expectedRouteFingerprint, context.budget());
            var answer = decision.require(rule.decisionQuestion);
            var route = decision.route().routeFingerprint();
            var outcome = outcome(rule, context, answer);

            if (outcome.pass) return QualityRuleEvaluation.pass(route);
            var issue = QualityIssues.of(rule, context.enforcement(),
                outcome.uncertain ? QualityIssueStatus.INDETERMINATE : QualityIssueStatus.FAIL,
                primaryField(rule), QualityEvidenceKind.SEMANTIC_ASSESSMENT,
                outcome.uncertain ? "DECISION_UNCERTAIN" : "DECISION_FAILED",
                QualityRecommendedAction.EDIT_INPUT, false, Map.of(),
                rule.messageTemplate, rule.remediationTemplate);
            DecisionSupport.attach(issue, new DecisionSupport.DecisionProof(rule.decisionType,
                rule.decisionQuestion, rule.decisionQuestion, rule.decisionPolarity,
                outcome.probability, context.upper(), context.lower(), outcome.mapping));
            return QualityRuleEvaluation.issue(issue.status, List.of(issue), route, null);
        } catch (QualityDecisionException e) {
            return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
                List.of(DecisionSupport.errorIssue(context, e, primaryField(rule),
                    "自定义语义规则未能完成")), null, e.kind().name());
        }
    }

    private DecisionQuestion question(ai.core.server.quality.domain.AgentQualityRuleRevision rule) {
        var type = rule.decisionType == null ? "noul" : rule.decisionType.toLowerCase(java.util.Locale.ROOT);
        var instructions = rule.decisionInstruction;
        return switch (type) {
            case "choice" -> DecisionQuestion.choice(rule.decisionQuestion, instructions,
                rule.decisionCriteria == null ? Map.of("yes", "符合", "no", "不符合") : rule.decisionCriteria);
            case "score" -> DecisionQuestion.score(rule.decisionQuestion, instructions,
                rule.decisionLevels == null || rule.decisionLevels.isEmpty()
                    ? List.of("low", "medium", "high") : rule.decisionLevels);
            default -> DecisionQuestion.noul(rule.decisionQuestion, instructions,
                rule.decisionCriteria == null ? Map.of("yes", "符合", "no", "不符合") : rule.decisionCriteria);
        };
    }

    private Outcome outcome(ai.core.server.quality.domain.AgentQualityRuleRevision rule,
                            QualityRuleContext context, ai.core.decision.DecisionAnswer answer) {
        var type = rule.decisionType == null ? "noul" : rule.decisionType.toLowerCase(java.util.Locale.ROOT);
        if ("choice".equals(type)) {
            var passLabels = parseLabels(rule.parameters == null ? null : rule.parameters.get("passLabels"));
            var winner = answer.choice();
            var probability = answer.probability();
            if (winner == null) return new Outcome(false, true, probability, "缺少 choice 结果时 INDETERMINATE");
            var pass = passLabels.isEmpty() ? !"no".equalsIgnoreCase(winner) : passLabels.contains(winner);
            if (probability == null || probability < context.upper()) {
                return new Outcome(false, true, probability, "winner 概率低于阈值时 INDETERMINATE");
            }
            return new Outcome(pass, false, probability,
                "choice 标签 " + winner + (pass ? " 视为符合" : " 视为不符合"));
        }
        if ("score".equals(type)) {
            var score = answer.score();
            var min = parseDouble(rule.parameters == null ? null : rule.parameters.get("minScore"), 0.5);
            var pass = score != null && score >= min;
            return new Outcome(pass, false, score,
                "score >= " + min + " 视为符合");
        }
        var probability = answer.probability();
        var verdict = DecisionSupport.verdict(probability, context.upper(), context.lower());
        return switch (verdict) {
            case AFFIRMATIVE -> new Outcome(true, false, probability, "肯定回答视为符合");
            case NEGATIVE -> new Outcome(false, false, probability, "否定回答视为不符合");
            case UNCERTAIN -> new Outcome(false, true, probability, "概率位于不确定区间");
        };
    }

    private String state(QualityRuleContext context) {
        var selector = context.rule().inputSelector == null || context.rule().inputSelector.isEmpty()
            ? List.of(CustomRuleInput.SYSTEM_PROMPT) : context.rule().inputSelector;
        var state = new LinkedHashMap<String, Object>();
        state.put("task", "publish_quality_custom_rule");
        state.put("rule", context.rule().ruleId + "@" + context.rule().revision);
        state.put("ruleRequirement", context.rule().description);
        if (selector.contains(CustomRuleInput.DESCRIPTION)) {
            state.put("description", context.snapshot().candidate().description());
        }
        if (selector.contains(CustomRuleInput.SYSTEM_PROMPT)) {
            state.put("systemPrompt", context.snapshot().candidate().prompt().systemPrompt());
        }
        if (selector.contains(CustomRuleInput.RESOURCE_SUMMARY)) {
            var resources = new ArrayList<Map<String, String>>();
            for (var reference : context.snapshot().references()) {
                var summary = new LinkedHashMap<String, String>();
                summary.put("type", reference.type());
                summary.put("id", reference.id());
                summary.put("status", reference.status().name());
                resources.add(summary);
            }
            state.put("configuredResources", resources);
        }
        if (selector.contains(CustomRuleInput.CAPABILITIES)) {
            state.put("capabilities", context.snapshot().capabilities().capabilities());
        }
        return JsonUtil.toJson(state);
    }

    private String primaryField(ai.core.server.quality.domain.AgentQualityRuleRevision rule) {
        var selector = rule.inputSelector == null || rule.inputSelector.isEmpty()
            ? List.of() : rule.inputSelector;
        if (selector.contains(CustomRuleInput.DESCRIPTION)) return "/description";
        if (selector.contains(CustomRuleInput.SYSTEM_PROMPT)) return "/system_prompt";
        return "/";
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

    private QualityRuleEvaluation error(QualityRuleContext context, String reason, String message) {
        return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
            List.of(QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.ERROR,
                "/", QualityEvidenceKind.SEMANTIC_ASSESSMENT, reason,
                QualityRecommendedAction.CONTACT_ADMIN, false, Map.of(), message,
                "联系规则管理员检查该规则定义。")), null, reason);
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
    public RuleTemplate template() {
        return new RuleTemplate(EVALUATOR_KEY, "自定义语义规则", "DECISION", "CUSTOM",
            "由管理员配置输入、问题和阈值的语义规则；不需要发布服务端代码。",
            List.of(new RuleParameterSpec("passLabels", "STRING", false,
                    "choice 规则中视为通过的标签，逗号分隔。", null, List.of()),
                new RuleParameterSpec("minScore", "DECIMAL", false,
                    "score 规则的最低通过分数。", "0.5", List.of())),
            QualityRuleRegistry.inputOptions(), true);
    }

    private record Outcome(boolean pass, boolean uncertain, Double probability, String mapping) {
    }
}



