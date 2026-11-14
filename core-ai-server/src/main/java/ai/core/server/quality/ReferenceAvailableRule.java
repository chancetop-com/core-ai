package ai.core.server.quality;

import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A1 for every reference except MCP servers, which have a dedicated rule.
 *
 * @author core-ai
 */
public class ReferenceAvailableRule implements QualityRuleEvaluator {
    public static final String KEY = "CFG.REFERENCE_AVAILABLE";

    @Override
    public QualityRuleEvaluation evaluate(QualityRuleContext context) {
        var issues = new ArrayList<StoredQualityIssue>();
        var status = QualityIssueStatus.PASS;

        for (var reference : context.snapshot().references()) {
            if ("MCP".equals(reference.type())) continue;
            if (reference.available()) continue;
            var issueStatus = reference.status() == QualityReferenceStatus.ERROR
                ? QualityIssueStatus.ERROR : QualityIssueStatus.FAIL;
            status = worst(status, issueStatus);
            var issue = QualityIssues.of(context.rule(), context.enforcement(), issueStatus,
                reference.fieldPath(), QualityEvidenceKind.CODE_FACT,
                reference.reason(), issueStatus == QualityIssueStatus.ERROR
                    ? QualityRecommendedAction.CONTACT_ADMIN : QualityRecommendedAction.EDIT_INPUT,
                issueStatus != QualityIssueStatus.ERROR,
                Map.of("resource_type", nullToEmpty(reference.type()),
                    "resource_id", nullToEmpty(reference.id())),
                message(reference), suggestion(reference));
            issue.resourceType = reference.type();
            issue.resourceId = reference.id();
            issues.add(issue);
        }
        return new QualityRuleEvaluation(status, issues, null, null);
    }

    private QualityIssueStatus worst(QualityIssueStatus current, QualityIssueStatus candidate) {
        if (current == QualityIssueStatus.ERROR || candidate == QualityIssueStatus.ERROR) return QualityIssueStatus.ERROR;
        if (current == QualityIssueStatus.FAIL || candidate == QualityIssueStatus.FAIL) return QualityIssueStatus.FAIL;
        return QualityIssueStatus.PASS;
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("CC_CYCLOMATIC_COMPLEXITY")
    private String message(ResolvedReference reference) {
        var label = reference.fieldPath() == null ? reference.type() : reference.fieldPath();
        return switch (nullToEmpty(reference.reason())) {
            case "SKILL_NOT_FOUND" -> label + " 引用的 skill 不存在。";
            case "BUILTIN_NOT_REGISTERED" -> label + " 引用的 builtin tool 未注册。";
            case "API_TOOL_NOT_FOUND" -> label + " 引用的 API-tool 不存在或不可用。";
            case "AGENT_NOT_FOUND", "LLM_CALL_NOT_FOUND" -> label + " 引用的 Agent 定义不存在。";
            case "AGENT_NOT_PUBLISHED" -> label + " 引用的 Agent 尚未发布可用配置。";
            case "LLM_CALL_NOT_PUBLISHED" -> label + " 引用的 LLM_CALL 尚未发布可用配置。";
            case "MALFORMED_REFERENCE" -> label + " 的引用格式不正确。";
            case "DATASET_NOT_FOUND" -> label + " 引用的 dataset 不存在。";
            case "MANAGED_PROMPT_NOT_FOUND" -> label + " 引用的受管提示词不存在。";
            case "TOOL_REGISTRY_UNAVAILABLE" -> label + " 的引用无法校验，工具注册表不可用。";
            default -> label + " 的引用不存在或不可用。";
        };
    }

    private String suggestion(ResolvedReference reference) {
        return switch (nullToEmpty(reference.type())) {
            case "SKILL" -> "移除此 skill，或重新选择有效且有权使用的 skill。";
            case "BUILTIN" -> "选择已注册的 builtin tool，或删除该引用。";
            case "API" -> "选择有效的 API-tool 绑定，或删除该引用。";
            case "AGENT", "SUBAGENT" -> "先发布被引用的 Agent，或移除该引用。";
            case "LLM_CALL" -> "先发布被引用的 LLM_CALL，或移除该引用。";
            case "DATASET" -> "选择存在的 dataset，或移除该绑定。";
            case "SYSTEM_PROMPT" -> "重新选择有效的受管提示词，或改用 inline prompt。";
            default -> "移除不可用的引用，或重新选择有效配置。";
        };
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
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
        return new RuleTemplate(KEY, "引用资源有效性", "CODE", "CONFIG",
            "所有配置引用必须存在、可用且类型正确；MCP server 由独立规则检查。",
            List.of(), List.of(), false);
    }
}
