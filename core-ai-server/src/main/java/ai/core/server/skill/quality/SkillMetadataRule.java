package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;

import java.util.regex.Pattern;

/**
 * Version, author and tags live in frontmatter metadata. Version must move forward when the package content
 * changes; repository imports keep their commit as the version source and therefore only warn.
 *
 * @author core-ai
 */
public final class SkillMetadataRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.METADATA_REQUIRED", 1, "Required metadata",
        "metadata must contain version, author and tags, and version must be comparable.", "METADATA",
        QualityEnforcement.BLOCK);
    private static final Pattern VERSION = Pattern.compile("^\\d+(\\.\\d+){1,2}([-+][0-9A-Za-z.-]+)?$");
    private static final Pattern CONTROL = Pattern.compile(".*[\\p{Cntrl}].*", Pattern.DOTALL);

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var version = candidate.metadata.get("version");
        if (blank(version)) return fail("MISSING_VERSION", "metadata 缺少 version。", "在 SKILL.md 的 metadata 中加入 version，如 1.0.0。");
        if (version.length() > 32 || !VERSION.matcher(version).matches()) {
            return fail("INVALID_VERSION", "version \"" + version + "\" 不是可比较的版本号。", "使用 MAJOR.MINOR.PATCH，如 1.2.0。");
        }
        var author = candidate.metadata.get("author");
        if (blank(author)) return fail("MISSING_AUTHOR", "metadata 缺少 author。", "在 metadata 中加入作者或团队标识。");
        var authorName = author.trim();
        if (authorName.length() > 128 || CONTROL.matcher(authorName).matches()) {
            return fail("INVALID_AUTHOR", "author 为空、过长或包含控制字符。", "使用 1–128 个可打印字符。");
        }
        var tags = candidate.metadata.get("tags");
        if (blank(tags)) return fail("MISSING_TAGS", "metadata 缺少 tags。", "在 metadata 中加入逗号分隔的标签，如 poster,image。");
        return versionBump(candidate, version);
    }

    private SkillRuleResult versionBump(SkillPublishCandidate candidate, String version) {
        if (!candidate.contentChanged || candidate.previousVersion == null) return SkillRuleResult.pass();
        if (candidate.isRepoSource()) return SkillRuleResult.pass();
        if (SkillVersions.compare(version, candidate.previousVersion) > 0) return SkillRuleResult.pass();
        return SkillRuleResult.fail(SkillQualityIssues.issue(META, QualityIssueStatus.FAIL, "/metadata/version",
            "VERSION_NOT_BUMPED",
            "技能内容已变化，但 version 没有递增（" + candidate.previousVersion + " → " + version + "）。",
            "把 metadata.version 提升到高于 " + candidate.previousVersion + " 的版本。"));
    }

    private SkillRuleResult fail(String reason, String message, String suggestion) {
        return SkillRuleResult.fail(SkillQualityIssues.issue(META, QualityIssueStatus.FAIL,
            "/metadata", reason, message, suggestion));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
