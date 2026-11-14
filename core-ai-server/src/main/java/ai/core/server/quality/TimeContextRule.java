package ai.core.server.quality;

import ai.core.decision.DecisionQuestion;
import ai.core.server.domain.DefinitionType;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B1. Code proves whether the real template injects system.time; only when it does not does the decision
 * model judge whether the instruction actually depends on the current clock. The narrow default scope avoids
 * blocking "output dates as YYYY-MM-DD"; the strict scope is a parameter, not a different rule.
 *
 * @author core-ai
 */
public class TimeContextRule implements QualityRuleEvaluator {
    public static final String KEY = "PROMPT.TIME_CONTEXT_REQUIRED";
    public static final String QUESTION = "time_instruction_present";
    public static final String PREDICATE = "提示词是否包含需要当前时钟才能完成的适用时间指令";

    @Override
    public QualityRuleEvaluation evaluate(QualityRuleContext context) {
        var prompt = context.systemPrompt();
        if (prompt == null || prompt.isBlank()) return QualityRuleEvaluation.pass(null);

        var type = context.snapshot().candidate().entity().type;
        var injected = type == DefinitionType.AGENT
            && context.snapshot().candidate().prompt().systemTimeInjected();
        if (injected) return QualityRuleEvaluation.pass(null);

        if (context.decisions() == null) {
            return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
                DecisionSupport.single(QualityIssues.of(context.rule(), context.enforcement(),
                    QualityIssueStatus.ERROR, context.fieldPath(), QualityEvidenceKind.SEMANTIC_ASSESSMENT,
                    "DECISION_UNAVAILABLE", QualityRecommendedAction.CONTACT_ADMIN, false, Map.of(),
                    "时间指令检查无法执行：决策模型不可用。", "联系规则管理员检查决策模型路由。")),
                null, "DECISION_UNAVAILABLE");
        }

        var scope = context.rule().parameters == null ? null : context.rule().parameters.get("temporalScope");
        if (scope == null || scope.isBlank()) scope = "CURRENT_TIME_DEPENDENT";

        var state = JsonUtil.toJson(Map.of(
            "task", "publish_quality_time_instruction",
            "temporalScope", scope,
            "definitionType", type == null ? "AGENT" : type.name(),
            "systemPrompt", prompt));
        var question = DecisionQuestion.noul(QUESTION, instruction(scope), criteria());
        try {
            var decision = context.decisions().decide(context.rule().modelAlias, state,
                List.of(question), context.rule().expectedRouteFingerprint, context.budget());
            var probability = decision.require(QUESTION).probability();
            var verdict = DecisionSupport.verdict(probability, context.upper(), context.lower());
            var route = decision.route().routeFingerprint();

            if (verdict == DecisionSupport.Verdict.NEGATIVE) return QualityRuleEvaluation.pass(route);

            var issue = QualityIssues.of(context.rule(), context.enforcement(),
                verdict == DecisionSupport.Verdict.AFFIRMATIVE
                    ? QualityIssueStatus.FAIL : QualityIssueStatus.INDETERMINATE,
                context.fieldPath(), QualityEvidenceKind.SEMANTIC_ASSESSMENT,
                verdict == DecisionSupport.Verdict.AFFIRMATIVE
                    ? "MISSING_TIME_CONTEXT" : "TIME_INTENT_UNCERTAIN",
                QualityRecommendedAction.EDIT_INPUT, false,
                Map.of("definition_type", type == null ? "AGENT" : type.name()),
                message(verdict, type), suggestion(type));
            DecisionSupport.attach(issue, DecisionSupport.requiredIntent(QUESTION, PREDICATE, probability,
                context, "时间指令为肯定且有效 system.time 注入为 false 时 FAIL；否定时 PASS；区间内为 INDETERMINATE"));
            return QualityRuleEvaluation.issue(issue.status, List.of(issue), route, null);
        } catch (QualityDecisionException e) {
            return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
                List.of(DecisionSupport.errorIssue(context, e, context.fieldPath(),
                    "时间指令检查未能完成")), null, e.kind().name());
        }
    }

    private String instruction(String scope) {
        if ("ANY_TEMPORAL_INSTRUCTION".equals(scope)) {
            return "严格模式：判断提示词中是否存在要求 Agent 处理具体日期、时间、时区、期限或相对时间的适用指令。"
                + "纯背景引文、用户输入示例、格式说明中的日期不视为指令。";
        }
        return "判断提示词是否包含必须依赖“当前时钟”才能执行的指令，例如今天、本周、最近 N 天、当前年份、"
            + "是否过期、从现在起算。日期格式说明、解析用户提供的日期、固定历史日期转换、时区换算不算。";
    }

    private Map<String, String> criteria() {
        var criteria = new LinkedHashMap<String, String>();
        criteria.put("yes", "提示词包含适用的时间指令，需要当前时间上下文");
        criteria.put("no", "提示词不包含适用的时间指令，或仅做格式/固定日期处理");
        return criteria;
    }

    private String message(DecisionSupport.Verdict verdict, DefinitionType type) {
        if (verdict == DecisionSupport.Verdict.UNCERTAIN) {
            return "无法可靠判断提示词是否要求当前时间；未发现有效 system.time 注入。";
        }
        if (type == DefinitionType.LLM_CALL) {
            return "提示词包含适用的时间指令，但 LLM_CALL 执行路径不会渲染 system.time。";
        }
        return "提示词包含适用的时间指令，但未发现有效 system.time 注入。";
    }

    private String suggestion(DefinitionType type) {
        if (type == DefinitionType.LLM_CALL) {
            return "LLM_CALL 不支持时间模板：改用支持渲染的 AGENT，或移除需要当前时间的指令；"
                + "仅补字面 {{system.time}} 不会生效。";
        }
        return "在参与运行时渲染的 system prompt 中加入：当前时间：{{system.time}}，或删除不需要的时间指令。";
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
        return new RuleTemplate(KEY, "时间指令必须有有效时间上下文", "HYBRID", "PROMPT",
            "适用的时间指令必须配合真实渲染的 system.time 注入。",
            List.of(new RuleParameterSpec("temporalScope", "ENUM", false,
                "时间指令范围：CURRENT_TIME_DEPENDENT（默认，仅当前时钟依赖）或 ANY_TEMPORAL_INSTRUCTION（严格）。",
                "CURRENT_TIME_DEPENDENT", List.of("CURRENT_TIME_DEPENDENT", "ANY_TEMPORAL_INSTRUCTION"))),
            List.of(), true);
    }
}

