package ai.core.server.quality;

import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;

import java.util.List;
import java.util.Map;

/**
 * A1 MCP tool level. The registry does not persist a server's tool list, and connecting a server during
 * publish is a documented non-goal, so an unverified tool is INDETERMINATE, never PASS.
 *
 * @author core-ai
 */
public class McpToolAvailableRule implements QualityRuleEvaluator {
    public static final String KEY = "CFG.MCP_TOOL_AVAILABLE";
    public static final String UNVERIFIED = "MCP_TOOL_LIST_UNAVAILABLE";

    @Override
    public QualityRuleEvaluation evaluate(QualityRuleContext context) {
        var unverified = context.snapshot().references().stream()
            .filter(reference -> "MCP".equals(reference.type())
                && reference.id().startsWith("mcp-tool:")
                && reference.available()
                && (reference.reason() == null || !reference.reason().startsWith("TOOL:")))
            .findFirst().orElse(null);
        if (unverified == null) return QualityRuleEvaluation.pass(null);
        var issue = QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.INDETERMINATE,
            unverified.fieldPath(), QualityEvidenceKind.CODE_FACT, UNVERIFIED,
            QualityRecommendedAction.CONTACT_ADMIN, false,
            Map.of("resource_id", unverified.id()), null, null);
        issue.resourceType = "MCP_TOOL";
        issue.resourceId = unverified.id();
        return QualityRuleEvaluation.issue(QualityIssueStatus.INDETERMINATE, List.of(issue), null, UNVERIFIED);
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
        return new RuleTemplate(KEY, "MCP tool 存在性", "CODE", "CONFIG",
            "固定 MCP tool 的存在性需要可信 tool 列表缓存；未缓存时返回未验证，不伪装通过。",
            List.of(), List.of(), false);
    }
}

