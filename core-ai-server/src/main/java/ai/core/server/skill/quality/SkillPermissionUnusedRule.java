package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;

import java.util.List;

/**
 * A declaration that no detected call uses is usually stale or over-broad. It is visible but not blocking,
 * because dynamic code may still need it.
 *
 * @author core-ai
 */
public final class SkillPermissionUnusedRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.PERMISSION_UNUSED", 1, "Unused capability declarations",
        "Declared capabilities with no detected usage should be narrowed.", "PERMISSION",
        QualityEnforcement.WARN);
    private final SkillPermissionAnalyzer analyzer = new SkillPermissionAnalyzer();

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var declaration = SkillPermissionDeclaration.parse(candidate.allowedTools);
        var usages = analyzer.analyze(candidate);
        var issues = new java.util.ArrayList<ai.core.server.quality.domain.StoredQualityIssue>();
        for (var entry : declaration.entries()) {
            if (!used(entry, usages)) {
                issues.add(SkillQualityIssues.issue(META, QualityIssueStatus.PASS, "/allowed-tools",
                    "UNUSED_PERMISSION",
                    "声明了但未检测到使用：" + render(entry),
                    "如果动态代码不需要它，移除该声明以缩小能力范围。"));
            }
        }
        return new SkillRuleResult(QualityIssueStatus.PASS, issues);
    }

    private boolean used(SkillPermissionDeclaration.Entry entry, List<SkillDetectedUsage> usages) {
        for (var usage : usages) {
            if (usage.dynamic()) continue;
            if (entryMatches(entry, usage)) return true;
        }
        return false;
    }

    private boolean entryMatches(SkillPermissionDeclaration.Entry entry, SkillDetectedUsage usage) {
        var declaration = SkillPermissionDeclaration.parse(List.of(render(entry)));
        return declaration.covers(usage);
    }

    private String render(SkillPermissionDeclaration.Entry entry) {
        if (entry.parts().isEmpty()) return entry.kind();
        return entry.kind() + ":" + String.join("/", entry.parts());
    }
}
