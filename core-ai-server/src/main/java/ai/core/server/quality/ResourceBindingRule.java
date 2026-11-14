package ai.core.server.quality;

import ai.core.decision.DecisionQuestion;
import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * B3. Code first locates explicit ids and configured names; the model only judges whether a located name is
 * actually required. It is never asked to invent a resource list, so an unknown mention is reported at field
 * level instead of a fabricated id.
 *
 * @author core-ai
 */
public class ResourceBindingRule implements QualityRuleEvaluator {
    public static final String KEY = "PROMPT.RESOURCE_BINDING_REQUIRED";
    public static final String GLOBAL_QUESTION = "uncovered_resource_required";
    private static final int MAX_CANDIDATES = 12;
    private static final Pattern EXPLICIT_ID = Pattern.compile(
        "(?:api-app|api-service|api-operation|mcp-tool|builtin|llm-call):[A-Za-z0-9_.:\\-]+");

    @Inject
    QualityReferenceResolver referenceResolver;

    @Override
    public QualityRuleEvaluation evaluate(QualityRuleContext context) {
        var prompt = context.systemPrompt();
        if (prompt == null || prompt.isBlank()) return QualityRuleEvaluation.pass(null);
        if (context.decisions() == null) {
            return error(context, "DECISION_UNAVAILABLE", "资源绑定检查无法执行：决策模型不可用。");
        }

        var configuredNames = referenceResolver.configuredResourceNames(
            context.snapshot().candidate().entity(), context.snapshot().candidate().config());
        var candidates = candidates(prompt, context, configuredNames);
        var state = JsonUtil.toJson(state(prompt, candidates, configuredNames));
        var questions = new ArrayList<DecisionQuestion>();
        for (int i = 0; i < candidates.size(); i++) {
            questions.add(DecisionQuestion.noul(questionName(i), instruction(candidates.get(i)), criteria()));
        }
        questions.add(DecisionQuestion.noul(GLOBAL_QUESTION,
            "除了已列出的候选资源外，提示词是否还要求使用任何未在已配置资源中出现的工具、"
                + "API、MCP server、skill、sub-agent 或 dataset？无法确定资源名时也回答 yes。", criteria()));

        try {
            var decision = context.decisions().decide(context.rule().modelAlias, state, questions,
                context.rule().expectedRouteFingerprint, context.budget());
            var route = decision.route().routeFingerprint();
            var issues = new ArrayList<StoredQualityIssue>();
            var status = candidateIssues(candidates, decision, context, issues);
            status = worse(status, globalIssue(decision, context, issues));
            return new QualityRuleEvaluation(status, issues, route, null);
        } catch (QualityDecisionException e) {
            return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
                List.of(DecisionSupport.errorIssue(context, e, context.fieldPath(), "资源绑定检查未能完成")),
                null, e.kind().name());
        }
    }

    private QualityIssueStatus candidateIssues(List<Candidate> candidates, ValidatedDecision decision,
                                               QualityRuleContext context, List<StoredQualityIssue> issues) {
        var status = QualityIssueStatus.PASS;
        for (int i = 0; i < candidates.size(); i++) {
            var candidate = candidates.get(i);
            if (candidate.bound()) continue;
            var probability = decision.require(questionName(i)).probability();
            var verdict = DecisionSupport.verdict(probability, context.upper(), context.lower());
            if (verdict == DecisionSupport.Verdict.NEGATIVE) continue;
            var fail = verdict == DecisionSupport.Verdict.AFFIRMATIVE;
            status = worse(status, fail ? QualityIssueStatus.FAIL : QualityIssueStatus.INDETERMINATE);
            var issue = QualityIssues.of(context.rule(), context.enforcement(),
                fail ? QualityIssueStatus.FAIL : QualityIssueStatus.INDETERMINATE,
                context.fieldPath(), QualityEvidenceKind.SEMANTIC_ASSESSMENT,
                fail ? "RESOURCE_NOT_BOUND" : "RESOURCE_USE_UNCERTAIN",
                QualityRecommendedAction.EDIT_INPUT, false,
                Map.of("resource_id", candidate.name(), "resource_type", candidate.type()),
                fail ? "提示词要求使用 " + candidate.type() + " " + candidate.name() + "，但配置中没有对应绑定。"
                    : "无法可靠判断提示词是否要求使用 " + candidate.type() + " " + candidate.name() + "，且未绑定。",
                fail ? "选择并绑定有效资源，或删除这项调用指令。" : "确认是否需要该资源；如需要请绑定对应配置。");
            issue.resourceType = candidate.type();
            issue.resourceId = candidate.name();
            DecisionSupport.attach(issue, DecisionSupport.requiredIntent(questionName(i),
                "提示词是否要求实际使用该命名资源", probability, context,
                "要求使用且未绑定时 FAIL；否定时 PASS；区间内为 INDETERMINATE"));
            issues.add(issue);
        }
        return status;
    }

    private QualityIssueStatus globalIssue(ValidatedDecision decision, QualityRuleContext context,
                                           List<StoredQualityIssue> issues) {
        var probability = decision.require(GLOBAL_QUESTION).probability();
        var verdict = DecisionSupport.verdict(probability, context.upper(), context.lower());
        if (verdict == DecisionSupport.Verdict.NEGATIVE) return QualityIssueStatus.PASS;
        var fail = verdict == DecisionSupport.Verdict.AFFIRMATIVE;
        var issue = QualityIssues.of(context.rule(), context.enforcement(),
            fail ? QualityIssueStatus.FAIL : QualityIssueStatus.INDETERMINATE,
            context.fieldPath(), QualityEvidenceKind.SEMANTIC_ASSESSMENT,
            fail ? "UNCOVERED_RESOURCE_REFERENCE" : "UNCOVERED_RESOURCE_UNCERTAIN",
            QualityRecommendedAction.EDIT_INPUT, false, Map.of(),
            fail ? "语义检查认为提示词要求的部分资源未被配置覆盖；当前无法可靠定位资源名。"
                : "无法可靠判断提示词是否要求未配置的资源。",
            "核对工具、技能、子 Agent 和数据集的调用指令，使用明确名称并配置对应资源。");
        DecisionSupport.attach(issue, DecisionSupport.requiredIntent(GLOBAL_QUESTION,
            "提示词是否要求任何配置无法覆盖的资源", probability, context,
            "存在未覆盖资源要求时 FAIL；否定时 PASS；区间内为 INDETERMINATE"));
        issues.add(issue);
        return fail ? QualityIssueStatus.FAIL : QualityIssueStatus.INDETERMINATE;
    }

    private List<Candidate> candidates(String prompt, QualityRuleContext context,
                                       Map<String, String> configuredNames) {
        var result = new ArrayList<Candidate>();
        var seen = new LinkedHashSet<String>();
        var matcher = EXPLICIT_ID.matcher(prompt);
        while (matcher.find() && result.size() < MAX_CANDIDATES) {
            var id = matcher.group();
            if (!seen.add("ID|" + id)) continue;
            result.add(new Candidate(id, typeOf(id), isBound(id, context), "EXPLICIT_ID"));
        }
        for (var entry : configuredNames.entrySet()) {
            if (result.size() >= MAX_CANDIDATES) break;
            var name = entry.getKey();
            if (name == null || name.length() < 3 || !prompt.contains(name)) continue;
            if (!seen.add("NAME|" + name)) continue;
            result.add(new Candidate(name, entry.getValue(), true, "CONFIGURED_NAME"));
        }
        return result;
    }

    private boolean isBound(String id, QualityRuleContext context) {
        var config = context.snapshot().candidate().config();
        if (config.tools == null) return false;
        for (var ref : config.tools) {
            if (ref == null || ref.id == null) continue;
            if (ref.id.equals(id)) return true;
            if (id.startsWith("mcp-tool:") && ref.type == ToolSourceType.MCP && mcpBound(id, ref)) return true;
        }
        return false;
    }

    private boolean mcpBound(String id, ToolRef ref) {
        var parsed = ToolRef.parseMcpToolId(id, null);
        if (parsed == null) return false;
        var configured = ToolRef.parseMcpToolId(ref.id, ref.source);
        if (configured != null && parsed.serverId() != null && parsed.serverId().equals(configured.serverId())) {
            return true;
        }
        return ref.id.equals(parsed.serverId());
    }

    private String typeOf(String id) {
        if (id.startsWith("api-")) return "API";
        if (id.startsWith("mcp-tool:")) return "MCP";
        if (id.startsWith("builtin:")) return "BUILTIN";
        if (id.startsWith("llm-call:")) return "LLM_CALL";
        return "TOOL";
    }

    private Map<String, Object> state(String prompt, List<Candidate> candidates,
                                      Map<String, String> configuredNames) {
        var state = new LinkedHashMap<String, Object>();
        state.put("task", "publish_quality_resource_binding");
        state.put("systemPrompt", prompt);
        state.put("candidates", candidates.stream().map(candidate -> Map.of(
            "name", candidate.name(), "type", candidate.type(),
            "bound", candidate.bound(), "locatedBy", candidate.locatedBy())).toList());
        state.put("configuredResources", configuredNames.entrySet().stream()
            .map(entry -> Map.of("name", entry.getKey(), "type", entry.getValue())).toList());
        return state;
    }

    private String questionName(int index) {
        return "resource_required_" + index;
    }

    private String instruction(Candidate candidate) {
        return "提示词是否要求 Agent 实际使用 " + candidate.type() + " \"" + candidate.name()
            + "\"？仅提及、举例、说明文档或明确禁止使用不算。";
    }

    private Map<String, String> criteria() {
        var criteria = new LinkedHashMap<String, String>();
        criteria.put("yes", "提示词要求实际使用该资源，或要求了未覆盖的资源");
        criteria.put("no", "提示词没有要求实际使用该资源");
        return criteria;
    }

    private QualityIssueStatus worse(QualityIssueStatus current, QualityIssueStatus candidate) {
        if (current == QualityIssueStatus.ERROR || candidate == QualityIssueStatus.ERROR) return QualityIssueStatus.ERROR;
        if (current == QualityIssueStatus.FAIL || candidate == QualityIssueStatus.FAIL) return QualityIssueStatus.FAIL;
        if (current == QualityIssueStatus.INDETERMINATE || candidate == QualityIssueStatus.INDETERMINATE) {
            return QualityIssueStatus.INDETERMINATE;
        }
        return current;
    }

    private QualityRuleEvaluation error(QualityRuleContext context, String reason, String message) {
        return QualityRuleEvaluation.issue(QualityIssueStatus.ERROR,
            List.of(QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.ERROR,
                context.fieldPath(), QualityEvidenceKind.SEMANTIC_ASSESSMENT, reason,
                QualityRecommendedAction.CONTACT_ADMIN, false, Map.of(), message,
                "联系规则管理员检查决策模型路由。")), null, reason);
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
        return new RuleTemplate(KEY, "指令所需资源与配置一致", "HYBRID", "PROMPT",
            "提示词中实际要求使用的命名资源必须有对应配置；未知名称只做字段级提示。",
            List.of(new RuleParameterSpec("catalogLimit", "INTEGER", false,
                "候选目录上限，默认 2000。", "2000", List.of())), List.of(), true);
    }

    private record Candidate(String name, String type, boolean bound, String locatedBy) {
    }
}
