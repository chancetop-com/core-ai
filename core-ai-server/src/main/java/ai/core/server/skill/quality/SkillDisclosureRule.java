package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.ArrayList;
/**
 * Progressive disclosure: the five content requirements may live in a packaged reference, but the link must
 * be reachable from SKILL.md and the target must be in this upload. A requirement that only points at the
 * network is not verifiable at publish time.
 *
 * @author core-ai
 */
public final class SkillDisclosureRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.PROGRESSIVE_DISCLOSURE", 1, "Progressive disclosure",
        "workflow/decision/output/error/QA must be present, either in SKILL.md or in a reachable packaged reference.", "CONTENT",
        QualityEnforcement.BLOCK);
    private final SkillDisclosureResolver resolver = new SkillDisclosureResolver();

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var resolution = resolver.resolve(candidate);
        var issues = new ArrayList<StoredQualityIssue>();
        var status = QualityIssueStatus.PASS;
        for (var requirement : SkillDisclosureRequirements.ALL) {
            if (resolution.sections().containsKey(requirement.key())) continue;
            if (resolution.tooLarge().containsKey(requirement.key())) {
                if (status != QualityIssueStatus.FAIL) status = QualityIssueStatus.INDETERMINATE;
                issues.add(issue(QualityIssueStatus.INDETERMINATE, "/SKILL.md", "SECTION_TOO_LARGE",
                    resolution.tooLarge().get(requirement.key()),
                    "Add the matching heading, or split the reference into sections under 8000 characters."));
                continue;
            }
            if (status != QualityIssueStatus.INDETERMINATE) status = QualityIssueStatus.FAIL;
            issues.add(issue(QualityIssueStatus.FAIL, "/SKILL.md", "MISSING_SECTION",
                "No " + requirement.label() + " section was found, and no reachable packaged reference provides it.",
                "Add a \"## " + requirement.label() + "\" section, or link a reference that contains it."));
        }
        return new SkillRuleResult(status, issues);
    }

    private StoredQualityIssue issue(QualityIssueStatus status, String path, String reason,
                                     String message, String suggestion) {
        return SkillQualityIssues.issue(META, status, path, reason, message, suggestion);
    }
}
