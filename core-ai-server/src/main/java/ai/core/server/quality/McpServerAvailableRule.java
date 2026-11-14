package ai.core.server.quality;

import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * A1 MCP server level: the server entry must exist, be enabled, be typed MCP and carry a config that the
 * runtime parser accepts. Connection health is deliberately not part of a publish gate.
 *
 * @author core-ai
 */
public class McpServerAvailableRule implements QualityRuleEvaluator {
    public static final String KEY = "CFG.MCP_SERVER_AVAILABLE";

    @Override
    public QualityRuleEvaluation evaluate(QualityRuleContext context) {
        var issues = new ArrayList<StoredQualityIssue>();
        var status = QualityIssueStatus.PASS;
        for (var reference : context.snapshot().references()) {
            if (!"MCP".equals(reference.type()) || reference.available()) continue;
            var issueStatus = reference.status() == QualityReferenceStatus.ERROR
                ? QualityIssueStatus.ERROR : QualityIssueStatus.FAIL;
            status = worst(status, issueStatus);
            var issue = QualityIssues.of(context.rule(), context.enforcement(), issueStatus,
                reference.fieldPath(), QualityEvidenceKind.CODE_FACT, reference.reason(),
                issueStatus == QualityIssueStatus.ERROR
                    ? QualityRecommendedAction.CONTACT_ADMIN : QualityRecommendedAction.EDIT_INPUT,
                issueStatus != QualityIssueStatus.ERROR,
                Map.of("resource_id", nullToEmpty(reference.id())),
                message(reference), "选择存在的 MCP server 并确保已启用、配置可解析，或移除该引用。");
            issue.resourceType = "MCP";
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

    private String message(ResolvedReference reference) {
        var label = reference.fieldPath() == null ? "MCP server" : reference.fieldPath();
        return switch (nullToEmpty(reference.reason())) {
            case "MCP_SERVER_NOT_FOUND" -> label + " 引用的 MCP server 不存在。";
            case "MCP_SERVER_DISABLED" -> label + " 引用的 MCP server 已被禁用。";
            case "MCP_CONFIG_INVALID" -> label + " 引用的 MCP server 配置无法解析。";
            case "TYPE_MISMATCH" -> label + " 引用的资源不是 MCP server。";
            case "MCP_SERVER_NOT_RESOLVED" -> label + " 无法从引用中解析出 MCP server。";
            case "TOOL_REGISTRY_UNAVAILABLE" -> label + " 无法校验，工具注册表不可用。";
            default -> label + " 的 MCP server 不可用。";
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
        return new RuleTemplate(KEY, "MCP server 有效性", "CODE", "CONFIG",
            "MCP server 引用必须存在、启用、类型正确且配置可解析；不探测连接状态。",
            List.of(), List.of(), false);
    }
}
