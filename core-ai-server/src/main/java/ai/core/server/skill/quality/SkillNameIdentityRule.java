package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.skill.SkillMetadata;

/**
 * The skill identity is the frontmatter name; the directory must agree so later updates cannot land on a
 * different catalog entry than the one the author edited.
 *
 * @author core-ai
 */
public final class SkillNameIdentityRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.NAME_IDENTITY", 1, "Name matches directory",
        "The SKILL.md name must follow the naming rules and match the uploaded directory name.", "IDENTITY",
        QualityEnforcement.BLOCK);

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        if (!SkillMetadata.isValidName(candidate.name)) {
            return SkillRuleResult.fail(SkillQualityIssues.issue(META, QualityIssueStatus.FAIL, "/name",
                "INVALID_SKILL_NAME",
                "The skill name is invalid: it must start with a lowercase letter or digit and contain only lowercase letters, digits and hyphens.",
                "Use a frontmatter name such as my-skill-name."));
        }
        if (candidate.directoryName == null || !candidate.name.equals(candidate.directoryName)) {
            if (candidate.isRepoSource()) {
                var issue = SkillQualityIssues.issue(META, QualityIssueStatus.PASS, "/name",
                    "SKILL_DIRECTORY_MISMATCH_REPO",
                    "Skill name \"" + candidate.name + "\" does not match repository directory \"" + candidate.directoryName + "\".",
                    "Align the directory name with the frontmatter name when possible.");
                issue.enforcement = QualityEnforcement.WARN;
                return SkillRuleResult.warn(issue);
            }
            return SkillRuleResult.fail(SkillQualityIssues.issue(META, QualityIssueStatus.FAIL, "/name",
                "SKILL_DIRECTORY_MISMATCH",
                "Skill name \"" + candidate.name + "\" does not match directory \"" + candidate.directoryName + "\".",
                "Rename the skill directory to \"" + candidate.name + "\" or change the frontmatter name."));
        }
        return SkillRuleResult.pass();
    }
}
