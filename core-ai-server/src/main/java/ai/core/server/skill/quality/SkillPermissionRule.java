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
        "SKILL.PERMISSION_DECLARATION", 1, "Capability declaration coverage",
        "core_ai_session calls in SKILL.md or scripts must be declared in allowed-tools.", "PERMISSION",
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
                "Unparseable capability declarations: " + String.join(", ", declaration.invalid()),
                "Use tool:/mcp:/api-tool:/subagent:/llm-call:/dataset:/media:/decision syntax."));
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
            "Unregistered builtin capability: " + name, "Use an existing tool or group, or check the spelling."));
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
                    "Cannot statically determine the capability called at " + usage.file() + ":" + usage.line() + ".",
                    "Use a literal name, or declare * for that namespace."));
            }
            return QualityIssueStatus.INDETERMINATE;
        }
        if (declaration.covers(usage)) return current;
        if (current == QualityIssueStatus.FAIL) return current;
        issues.add(issue(QualityIssueStatus.FAIL, "/" + usage.file(), "PERMISSION_NOT_DECLARED",
            "Undeclared capability call detected: " + describe(usage) + " (" + usage.file() + ":" + usage.line() + ").",
            "Declare " + suggestedDeclaration(usage) + " in allowed-tools."));
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
