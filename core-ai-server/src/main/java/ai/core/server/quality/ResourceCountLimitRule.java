package ai.core.server.quality;

import ai.core.server.domain.ToolRef;
import ai.core.server.domain.ToolSourceType;
import ai.core.server.quality.domain.QualityEvidenceKind;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.QualityRecommendedAction;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.server.util.IdLists;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * A2 counts distinct direct bindings per category. It deliberately does not count expanded runtime tool
 * schemas: a user who configured 10 MCP servers must not be told they exceeded a 50 "tool" limit.
 *
 * @author core-ai
 */
public class ResourceCountLimitRule implements QualityRuleEvaluator {
    public static final String KEY = "CFG.RESOURCE_COUNT_LIMIT";

    private static final List<String> CATEGORIES = List.of(
        "tool_refs_total", "builtin_refs", "api_refs", "mcp_servers", "skills", "subagents", "datasets");

    @Override
    public QualityRuleEvaluation evaluate(QualityRuleContext context) {
        var counts = counts(context);
        var issues = new ArrayList<StoredQualityIssue>();
        var status = QualityIssueStatus.PASS;

        for (var category : CATEGORIES) {
            var raw = context.rule().parameters == null ? null : context.rule().parameters.get(category);
            if (raw == null || raw.isBlank()) continue;
            int max;
            try {
                max = Integer.parseInt(raw.trim());
            } catch (NumberFormatException e) {
                status = QualityIssueStatus.ERROR;
                issues.add(QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.ERROR,
                    fieldPath(category), QualityEvidenceKind.CODE_FACT, "INVALID_RULE_PARAMETER",
                    QualityRecommendedAction.CONTACT_ADMIN, false, Map.of("category", category),
                    "规则参数 " + category + " 不是有效整数。", "联系规则管理员修正该数量上限。"));
                continue;
            }
            if (max < 0) {
                status = QualityIssueStatus.ERROR;
                issues.add(QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.ERROR,
                    fieldPath(category), QualityEvidenceKind.CODE_FACT, "INVALID_RULE_PARAMETER",
                    QualityRecommendedAction.CONTACT_ADMIN, false, Map.of("category", category),
                    "规则参数 " + category + " 不能为负数。", "联系规则管理员修正该数量上限。"));
                continue;
            }
            int actual = counts.getOrDefault(category, 0);
            if (actual <= max) continue;

            status = worst(status, QualityIssueStatus.FAIL);
            var issue = QualityIssues.of(context.rule(), context.enforcement(), QualityIssueStatus.FAIL,
                fieldPath(category), QualityEvidenceKind.CODE_FACT, "COUNT_LIMIT_EXCEEDED",
                QualityRecommendedAction.EDIT_INPUT, false,
                Map.of("category", category, "actual_count", String.valueOf(actual),
                    "max_count", String.valueOf(max), "reduction", String.valueOf(actual - max)),
                actual + " 个 " + label(category) + "，上限 " + max + " 个。",
                "至少减少 " + (actual - max) + " 个 " + label(category)
                    + "；保留必需项，或联系规则管理员调整上限。");
            issue.actualCount = actual;
            issue.maxCount = max;
            issues.add(issue);
        }
        return new QualityRuleEvaluation(status, issues, null, null);
    }

    private Map<String, Integer> counts(QualityRuleContext context) {
        var config = context.snapshot().candidate().config();
        var counts = new LinkedHashMap<String, Integer>();
        var tools = new LinkedHashSet<String>();
        var builtin = new LinkedHashSet<String>();
        var api = new LinkedHashSet<String>();
        var mcpServers = new LinkedHashSet<String>();
        if (config.tools != null) {
            for (var ref : config.tools) {
                if (ref == null || ref.id == null) continue;
                tools.add(key(ref.type, ref.id, ref.source));
                if (isBuiltin(ref)) builtin.add(key(ref.type, ref.id, ref.source));
                if (ref.type == ToolSourceType.API) api.add(key(ref.type, ref.id, ref.source));
                var serverId = mcpServerId(ref);
                if (serverId != null) mcpServers.add(serverId);
            }
        }
        counts.put("tool_refs_total", tools.size());
        counts.put("builtin_refs", builtin.size());
        counts.put("api_refs", api.size());
        counts.put("mcp_servers", mcpServers.size());
        counts.put("skills", new LinkedHashSet<>(IdLists.clean(config.skillIds)).size());
        counts.put("subagents", new LinkedHashSet<>(IdLists.clean(config.subAgentIds)).size());
        var datasets = new LinkedHashSet<String>();
        if (config.datasetConfig != null) {
            for (var dataset : config.datasetConfig) {
                if (dataset != null && dataset.datasetId != null) datasets.add(dataset.datasetId);
            }
        }
        counts.put("datasets", datasets.size());
        return counts;
    }

    private boolean isBuiltin(ToolRef ref) {
        if (ref.type == ToolSourceType.BUILTIN) return true;
        return ref.type == null && ref.id != null && (ref.id.startsWith("builtin-") || ref.id.startsWith("builtin:"));
    }

    private String mcpServerId(ToolRef ref) {
        if (ref.id == null) return null;
        if (ref.type == ToolSourceType.MCP || ref.id.startsWith("mcp-tool:") || ref.id.startsWith("config:")) {
            var parsed = ToolRef.parseMcpToolId(ref.id, ref.source);
            if (parsed != null) return parsed.serverId();
            return ref.id;
        }
        return null;
    }

    private String key(ToolSourceType type, String id, String source) {
        return (type == null ? "" : type.name()) + "|" + id + "|" + (source == null ? "" : source);
    }

    private String fieldPath(String category) {
        return switch (category) {
            case "skills" -> "/skill_ids";
            case "subagents" -> "/subagent_ids";
            case "datasets" -> "/dataset_config";
            default -> "/tools";
        };
    }

    private String label(String category) {
        return switch (category) {
            case "tool_refs_total" -> "工具引用";
            case "builtin_refs" -> "builtin 引用";
            case "api_refs" -> "API-tool 引用";
            case "mcp_servers" -> "MCP server";
            case "skills" -> "skill";
            case "subagents" -> "sub-agent";
            case "datasets" -> "dataset";
            default -> category;
        };
    }

    private QualityIssueStatus worst(QualityIssueStatus current, QualityIssueStatus candidate) {
        if (current == QualityIssueStatus.ERROR || candidate == QualityIssueStatus.ERROR) return QualityIssueStatus.ERROR;
        if (current == QualityIssueStatus.FAIL || candidate == QualityIssueStatus.FAIL) return QualityIssueStatus.FAIL;
        return QualityIssueStatus.PASS;
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
        return new RuleTemplate(KEY, "配置数量上限", "CODE", "CONFIG",
            "按类别限制 Agent 的直接引用数量；零允许，超过上限即失败。",
            CATEGORIES.stream().map(category -> new RuleParameterSpec(category, "INTEGER", false,
                "该类别允许的最大直接引用数量；留空表示不限制。", null, List.of())).toList(),
            List.of(), false);
    }
}
