package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;

/**
 * Repository and marketplace imports use the commit as their content version; a missing bump is visible but
 * does not block the sync.
 *
 * @author core-ai
 */
public final class SkillVersionWarningRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.VERSION_BUMP_REPO", 1, "Repo 内容版本提示",
        "repo/marketplace 内容变化时 version 未递增只提示，不阻断。", "METADATA",
        QualityEnforcement.WARN);

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        if (!candidate.isRepoSource()) return SkillRuleResult.pass();
        if (!candidate.contentChanged || candidate.previousVersion == null) return SkillRuleResult.pass();
        if (SkillVersions.compare(candidate.version, candidate.previousVersion) > 0) return SkillRuleResult.pass();
        return SkillRuleResult.warn(SkillQualityIssues.issue(META, QualityIssueStatus.PASS, "/metadata/version",
            "VERSION_NOT_BUMPED_REPO",
            "repo 内容已变化，但 metadata.version 未递增；本次仍按 commit 同步。",
            "建议在下次提交中提升 metadata.version。"));
    }
}
