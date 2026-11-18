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
        "SKILL.UNREFERENCED_REFERENCE", 1, "Unreferenced reference",
        "Markdown files not reachable from SKILL.md links should be reported.", "CONTENT",
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
                path + " is not reachable from any SKILL.md link, so it cannot satisfy a requirement.",
                "Link it from SKILL.md if it is needed, otherwise confirm it is only a template or note."));
        }
        return new SkillRuleResult(QualityIssueStatus.PASS, issues);
    }
}
