package ai.core.server.quality;

import ai.core.decision.DecisionQuestion;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * B2. The capability set is projected from the same registry runtime uses before asking whether the prompt
 * actually asks for the operation. One question per capability, never one choice question that makes
 * simultaneous needs mutually exclusive.
 *
 * @author core-ai
 */
public class BuiltinCapabilityRule implements QualityRuleEvaluator {
    public static final String KEY = "PROMPT.BUILTIN_CAPABILITY_REQUIRED";
    private static final List<String> ORDER = EffectiveCapabilityResolver.CAPABILITIES;

    @Override
    public QualityRuleEvaluation evaluate(QualityRuleContext context) {
        var prompt = context.systemPrompt();
        if (prompt == null || prompt.isBlank()) return QualityRuleEvaluation.pass(null);

        var capabilities = context.snapshot().capabilities();
        var missing = ORDER.stream().filter(capability -> !capabilities.has(capability)).toList();
        if (missing.isEmpty()) return QualityRuleEvaluation.pass(null);
        if (context.decisions() == null) {
            return error(context, "DECISION_UNAVAILABLE", "builtin 能力检查无法执行：决策模型不可用。");
        }

        var state = JsonUtil.toJson(Map.of(
            "task", "publish_quality_builtin_capability",
            "systemPrompt", prompt,
            "availableCapabilities", capabilities.capabilities(),
            "missingCapabilities", missing));
        var questions = new ArrayList<DecisionQuestion>();
        for (var capability : missing) {
            questions.add(DecisionQuestion.noul(questionName(capability), instruction(capability), criteria()));
        }
        try {
            var decision = context.decisions().decide(context.rule().modelAlias, state, questions,
                context.rule().expectedRouteFingerprint, context.budget());
            var route = decision.route().routeFingerprint();
            var outcome = collectIssues(missing, decision, context);
            return new QualityRuleEvaluation(outcome.status, outcome.issues, route, null);
        } catch (QualityDecisionException e) {
            return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
                List.of(DecisionSupport.errorIssue(context, e, context.fieldPath(), "builtin 能力检查未能完成")),
                null, e.kind().name());
        }
    }

    private CapabilityOutcome collectIssues(List<String> missing, ValidatedDecision decision,
                                            QualityRuleContext context) {
        var issues = new ArrayList<StoredQualityIssue>();
        var status = QualityIssueStatus.PASS;
        for (var capability : missing) {
            var probability = decision.require(questionName(capability)).probability();
            var verdict = DecisionSupport.verdict(probability, context.upper(), context.lower());
            if (verdict == DecisionSupport.Verdict.NEGATIVE) continue;
            var fail = verdict == DecisionSupport.Verdict.AFFIRMATIVE;
            if (fail) {
                status = QualityIssueStatus.FAIL;
            } else if (status == QualityIssueStatus.PASS) {
                status = QualityIssueStatus.INDETERMINATE;
            }
            var issue = QualityIssues.of(context.rule(), context.enforcement(),
                fail ? QualityIssueStatus.FAIL : QualityIssueStatus.INDETERMINATE,
                context.fieldPath(), QualityEvidenceKind.SEMANTIC_ASSESSMENT,
                fail ? "MISSING_BUILTIN_CAPABILITY" : "CAPABILITY_INTENT_UNCERTAIN",
                QualityRecommendedAction.EDIT_INPUT, false,
                Map.of("capability", capability),
                fail ? "提示词要求 " + capabilityLabel(capability) + "，但 Agent 缺少对应 builtin 能力。"
                    : "无法可靠判断提示词是否要求 " + capabilityLabel(capability) + "，且缺少对应 builtin 能力。",
                fail ? "启用对应 builtin（" + capabilityTools(capability) + "），或改为不需要该操作的指令。"
                    : "确认提示词是否需要该操作；如需要请启用对应 builtin。");
            DecisionSupport.attach(issue, DecisionSupport.requiredIntent(questionName(capability),
                predicate(capability), probability, context,
                "存在操作意图且对应能力不可用时 FAIL；否定时 PASS；区间内为 INDETERMINATE"));
            issues.add(issue);
        }
        return new CapabilityOutcome(status, issues);
    }


    private QualityRuleEvaluation error(QualityRuleContext context, String reason, String message) {
        return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
            List.of(QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.ERROR,
                context.fieldPath(), QualityEvidenceKind.SEMANTIC_ASSESSMENT, reason,
                QualityRecommendedAction.CONTACT_ADMIN, false, Map.of(), message,
                "联系规则管理员检查决策模型路由。")), null, reason);
    }

    private String questionName(String capability) {
        return "intent_" + capability;
    }

    private String predicate(String capability) {
        return "提示词是否要求 Agent 实际执行 " + capabilityLabel(capability) + " 操作";
    }

    private String instruction(String capability) {
        return switch (capability) {
            case EffectiveCapabilityResolver.FILE_READ ->
                "提示词是否要求 Agent 实际读取本地文件内容？解释如何读取、或只输出文本不算。";
            case EffectiveCapabilityResolver.FILE_WRITE ->
                "提示词是否要求 Agent 实际写入、编辑或保存文件到工作区？只输出 CSV 文本或代码示例不算。";
            case EffectiveCapabilityResolver.WEB_SEARCH ->
                "提示词是否要求 Agent 搜索互联网获取信息？总结用户贴入的网页内容不算。";
            case EffectiveCapabilityResolver.WEB_FETCH ->
                "提示词是否要求 Agent 抓取或打开某个 URL 的内容？仅提到 URL 不算。";
            case EffectiveCapabilityResolver.CODE_EXECUTE ->
                "提示词是否要求 Agent 实际运行代码、脚本或命令（包括安装依赖）？编写代码示例或解释代码不算。";
            case EffectiveCapabilityResolver.IMAGE_GENERATE ->
                "提示词是否要求 Agent 实际生成图片？写图片提示词或建议构图不算。";
            case EffectiveCapabilityResolver.IMAGE_EDIT ->
                "提示词是否要求 Agent 实际对参考图进行编辑或修改？只描述改图思路不算。";
            case EffectiveCapabilityResolver.VIDEO_GENERATE ->
                "提示词是否要求 Agent 实际生成或编辑视频？写分镜或视频提示词不算。";
            default -> "提示词是否要求 Agent 执行该操作？";
        };
    }

    private Map<String, String> criteria() {
        var criteria = new LinkedHashMap<String, String>();
        criteria.put("yes", "提示词明确要求 Agent 实际执行该操作");
        criteria.put("no", "提示词只解释、描述或禁止该操作，不要求实际执行");
        return criteria;
    }

    private String capabilityLabel(String capability) {
        return switch (capability) {
            case EffectiveCapabilityResolver.FILE_READ -> "读取本地文件";
            case EffectiveCapabilityResolver.FILE_WRITE -> "写入本地文件";
            case EffectiveCapabilityResolver.WEB_SEARCH -> "互联网搜索";
            case EffectiveCapabilityResolver.WEB_FETCH -> "抓取网页";
            case EffectiveCapabilityResolver.CODE_EXECUTE -> "运行代码";
            case EffectiveCapabilityResolver.IMAGE_GENERATE -> "生成图片";
            case EffectiveCapabilityResolver.IMAGE_EDIT -> "编辑图片";
            case EffectiveCapabilityResolver.VIDEO_GENERATE -> "生成视频";
            default -> capability;
        };
    }

    private String capabilityTools(String capability) {
        return switch (capability) {
            case EffectiveCapabilityResolver.FILE_READ -> "read_file";
            case EffectiveCapabilityResolver.FILE_WRITE -> "write_file 或 edit_file + read_file";
            case EffectiveCapabilityResolver.WEB_SEARCH -> "web_search";
            case EffectiveCapabilityResolver.WEB_FETCH -> "web_fetch";
            case EffectiveCapabilityResolver.CODE_EXECUTE -> "run_bash_command 或 run_python_script";
            case EffectiveCapabilityResolver.IMAGE_GENERATE -> "generate_image";
            case EffectiveCapabilityResolver.IMAGE_EDIT -> "generate_image（需 provider 支持图片编辑）";
            case EffectiveCapabilityResolver.VIDEO_GENERATE -> "generate_video + get_video_status";
            default -> capability;
        };
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
        return new RuleTemplate(KEY, "操作意图必须有 builtin 能力", "HYBRID", "PROMPT",
            "提示词要求执行文件、网络、代码、图片或视频操作时，必须配置对应 builtin 能力。",
            List.of(new RuleParameterSpec("providerPolicy", "ENUM", false,
                "能力来源策略；首版只支持 BUILTIN_REQUIRED。", "BUILTIN_REQUIRED",
                List.of("BUILTIN_REQUIRED"))), List.of(), true);
    }

    private record CapabilityOutcome(QualityIssueStatus status, List<StoredQualityIssue> issues) {
    }
}
