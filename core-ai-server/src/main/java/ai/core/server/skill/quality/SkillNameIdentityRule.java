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
                "Skill name 不符合命名规范：必须以小写字母或数字开头，只含小写字母、数字和连字符。",
                "把 frontmatter name 改为如 my-skill-name 的形式。"));
        }
        if (candidate.directoryName == null || !candidate.name.equals(candidate.directoryName)) {
            return SkillRuleResult.fail(SkillQualityIssues.issue(META, QualityIssueStatus.FAIL, "/name",
                "SKILL_DIRECTORY_MISMATCH",
                "Skill name \"" + candidate.name + "\" 与目录名 \"" + candidate.directoryName + "\" 不一致。",
                "把 skill 目录重命名为 \"" + candidate.name + "\"，或修改 frontmatter name。"));
        }
        return SkillRuleResult.pass();
    }
}
