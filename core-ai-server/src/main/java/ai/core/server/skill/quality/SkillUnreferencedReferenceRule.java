package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;

/**
 * A Markdown file that exists in the package but is not reachable from SKILL.md may be dead weight; it is
 * reported, not blocked, because templates and assets can legitimately be unreferenced.
 *
 * @author core-ai
 */
public final class SkillUnreferencedReferenceRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.UNREFERENCED_REFERENCE", 1, "未引用 reference",
        "未被 SKILL.md 可达链接引用的 Markdown 文件应提示。", "CONTENT",
        QualityEnforcement.WARN);

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var resolution = new SkillDisclosureResolver().resolve(candidate);
        var issues = new java.util.ArrayList<ai.core.server.quality.domain.StoredQualityIssue>();
        for (var path : candidate.resources.keySet()) {
            if (!path.endsWith(".md") || resolution.reachableFiles().contains(path)) continue;
            issues.add(SkillQualityIssues.issue(META, QualityIssueStatus.PASS, "/" + path,
                "UNREFERENCED_REFERENCE",
                path + " 没有被 SKILL.md 的可达链接引用，不能作为任一要求的内容来源。",
                "如果需要它满足某个要求，请从 SKILL.md 链接它；否则确认它是模板/说明文件。"));
        }
        return new SkillRuleResult(QualityIssueStatus.PASS, issues);
    }
}
