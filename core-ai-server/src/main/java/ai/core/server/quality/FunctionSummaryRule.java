package ai.core.server.quality;

import ai.core.decision.DecisionQuestion;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * C1. Non-empty is necessary but not sufficient: "the best assistant" passes a length check and tells a
 * reader nothing. The model only answers whether the text names one actual function.
 *
 * @author core-ai
 */
public class FunctionSummaryRule implements QualityRuleEvaluator {
    public static final String KEY = "DESC.FUNCTION_SUMMARY_REQUIRED";
    public static final String QUESTION = "function_summary_present";
    public static final String PREDICATE = "仅从该 description，读者能否明确知道 Agent 的至少一项实际功能或任务";

    @Override
    public QualityRuleEvaluation evaluate(QualityRuleContext context) {
        var description = context.snapshot().candidate().description();
        if (description == null || description.isBlank()) return emptyDescription(context);
        if (context.decisions() == null) return decisionUnavailable(context);

        var state = JsonUtil.toJson(Map.of("task", "publish_quality_function_summary", "description", description));
        var question = DecisionQuestion.noul(QUESTION,
            "判断读者是否能从该 description 明确知道 Agent 的至少一项实际功能或任务。"
                + "只有名字、版本号、标签、宣传语（如“最好用的智能助手”）不算；简短但具体的功能介绍算。",
            criteria());
        try {
            var decision = context.decisions().decide(context.rule().modelAlias, state, List.of(question),
                context.rule().expectedRouteFingerprint, context.budget());
            var probability = decision.require(QUESTION).probability();
            var verdict = DecisionSupport.verdict(probability, context.upper(), context.lower());
            var route = decision.route().routeFingerprint();
            if (verdict == DecisionSupport.Verdict.AFFIRMATIVE) return QualityRuleEvaluation.pass(route);

            var issue = QualityIssues.of(context.rule(), context.enforcement(),
                verdict == DecisionSupport.Verdict.NEGATIVE
                    ? QualityIssueStatus.FAIL : QualityIssueStatus.INDETERMINATE,
                "/description", QualityEvidenceKind.SEMANTIC_ASSESSMENT,
                verdict == DecisionSupport.Verdict.NEGATIVE
                    ? "FUNCTION_SUMMARY_MISSING" : "FUNCTION_SUMMARY_UNCERTAIN",
                QualityRecommendedAction.EDIT_INPUT, false, Map.of(),
                verdict == DecisionSupport.Verdict.NEGATIVE
                    ? "description 没有明确介绍 Agent 的功能。"
                    : "无法可靠判断 description 是否介绍了 Agent 的功能。",
                "补充“做什么 + 服务什么对象/产出什么”，例如“分析销售数据并生成周报”。");
            DecisionSupport.attach(issue, DecisionSupport.compliant(QUESTION, PREDICATE, probability,
                context, "description 明确介绍功能时 PASS；否定时 FAIL；区间内为 INDETERMINATE"));
            return QualityRuleEvaluation.issue(issue.status, List.of(issue), route, null);
        } catch (QualityDecisionException e) {
            return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
                List.of(DecisionSupport.errorIssue(context, e, "/description", "description 功能检查未能完成")),
                null, e.kind().name());
        }
    }

    private QualityRuleEvaluation emptyDescription(QualityRuleContext context) {
        var issue = QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.FAIL,
            "/description", QualityEvidenceKind.CODE_FACT, "DESCRIPTION_EMPTY",
            QualityRecommendedAction.EDIT_INPUT, false, Map.of(),
            "description 为空。",
            "补充“做什么 + 服务什么对象/产出什么”，例如“分析销售数据并生成周报”。");
        return QualityRuleEvaluation.fail(issue, null);
    }

    private QualityRuleEvaluation decisionUnavailable(QualityRuleContext context) {
        return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
            List.of(QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.ERROR,
                "/description", QualityEvidenceKind.SEMANTIC_ASSESSMENT, "DECISION_UNAVAILABLE",
                QualityRecommendedAction.CONTACT_ADMIN, false, Map.of(),
                "description 功能检查无法执行：决策模型不可用。",
                "联系规则管理员检查决策模型路由。")), null, "DECISION_UNAVAILABLE");
    }

    private Map<String, String> criteria() {
        var criteria = new LinkedHashMap<String, String>();
        criteria.put("yes", "description 明确介绍了至少一项实际功能或任务");
        criteria.put("no", "description 只有名字、标签、宣传语，或没有介绍任何实际功能");
        return criteria;
    }

    @Override
    public String evaluatorKey() {
        return KEY;
    }

    @Override
    public int version() {
        return 1;
    }

    @Override
    public RuleTemplate template() {
        return new RuleTemplate(KEY, "description 必须包含功能简介", "DECISION", "DESCRIPTION",
            "description 必须让读者明确知道 Agent 的至少一项实际功能。",
            List.of(), List.of(), true);
    }
}

