package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;

import java.util.ArrayList;
import java.util.List;

/**
 * Every statically detected capability use must be covered by a typed declaration. The check proves a
 * declaration exists; it does not grant the capability and it does not claim the runtime will allow it.
 *
 * @author core-ai
 */
public final class SkillPermissionRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.PERMISSION_DECLARATION", 1, "能力声明覆盖",
        "SKILL.md 或脚本中的 core_ai_session 调用必须在 allowed-tools 中声明。", "PERMISSION",
        QualityEnforcement.BLOCK);
    private final SkillPermissionAnalyzer analyzer = new SkillPermissionAnalyzer();

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var declaration = SkillPermissionDeclaration.parse(candidate.allowedTools);
        if (!declaration.invalid().isEmpty()) {
            return SkillRuleResult.fail(issue(QualityIssueStatus.FAIL, "/allowed-tools", "UNKNOWN_PERMISSION_NAMESPACE",
                "存在无法解析的能力声明：" + String.join(", ", declaration.invalid()),
                "使用 tool:/mcp:/api-tool:/subagent:/llm-call:/dataset:/media:/decision 语法。"));
        }
        var usage = analyzer.analyze(candidate);
        var issues = new ArrayList<ai.core.server.quality.domain.StoredQualityIssue>();
        var status = QualityIssueStatus.PASS;
        for (var entry : declaration.entries()) {
            status = validateEntry(entry, issues, status);
        }
        for (var detected : usage) {
            status = validateUsage(detected, declaration, issues, status);
        }
        return new SkillRuleResult(status, issues);
    }

    private QualityIssueStatus validateEntry(SkillPermissionDeclaration.Entry entry,
                                             List<ai.core.server.quality.domain.StoredQualityIssue> issues,
                                             QualityIssueStatus current) {
        if (!"tool".equals(entry.kind()) || entry.parts().isEmpty() || "*".equals(entry.parts().getFirst())) {
            return current;
        }
        var name = entry.parts().getFirst();
        if (SkillCapabilityCatalog.isKnownTool(name) || SkillCapabilityCatalog.isKnownGroup(name)) return current;
        if (current == QualityIssueStatus.FAIL) return current;
        issues.add(issue(QualityIssueStatus.FAIL, "/allowed-tools", "UNKNOWN_BUILTIN_TOOL",
            "未注册的 builtin 能力：" + name, "改用实际存在的 tool 或 group，或检查拼写。"));
        return QualityIssueStatus.FAIL;
    }

    private QualityIssueStatus validateUsage(SkillDetectedUsage usage, SkillPermissionDeclaration declaration,
                                             List<ai.core.server.quality.domain.StoredQualityIssue> issues,
                                             QualityIssueStatus current) {
        if ("dataset".equals(usage.kind())) return current;
        if (usage.dynamic()) {
            if (declaration.coversNamespace(usage.kind())) return current;
            if (current == QualityIssueStatus.PASS) {
                issues.add(issue(QualityIssueStatus.INDETERMINATE, "/" + usage.file(),
                    "DYNAMIC_USAGE",
                    "无法静态确定 " + usage.file() + ":" + usage.line() + " 调用的能力名称。",
                    "改用字面量名称，或为该 namespace 声明 * 宽权限。"));
            }
            return QualityIssueStatus.INDETERMINATE;
        }
        if (declaration.covers(usage)) return current;
        if (current == QualityIssueStatus.FAIL) return current;
        issues.add(issue(QualityIssueStatus.FAIL, "/" + usage.file(), "PERMISSION_NOT_DECLARED",
            "检测到未声明的能力调用：" + describe(usage) + "（" + usage.file() + ":" + usage.line() + "）。",
            "在 allowed-tools 中声明 " + suggestedDeclaration(usage) + "。"));
        return QualityIssueStatus.FAIL;
    }

    private String describe(SkillDetectedUsage usage) {
        return usage.kind() + ":" + usage.joinedPath();
    }

    private String suggestedDeclaration(SkillDetectedUsage usage) {
        if (usage.path().isEmpty()) return usage.kind() + ":*";
        return switch (usage.kind()) {
            case "tool" -> "tool:" + usage.path().getFirst();
            case "mcp" -> "mcp:" + usage.path().getFirst();
            case "api-tool" -> "api-tool:" + String.join("/", usage.path());
            case "subagent" -> "subagent:" + usage.path().getFirst();
            case "llm-call" -> "llm-call:" + usage.path().getFirst();
            default -> usage.kind() + ":" + usage.joinedPath();
        };
    }

    private ai.core.server.quality.domain.StoredQualityIssue issue(QualityIssueStatus status, String path,
                                                                 String reason, String message, String suggestion) {
        return SkillQualityIssues.issue(META, status, path, reason, message, suggestion);
    }
}
