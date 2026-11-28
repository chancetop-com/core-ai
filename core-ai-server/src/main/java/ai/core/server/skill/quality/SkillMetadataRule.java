package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.ArrayList;
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
        var issues = new ArrayList<StoredQualityIssue>();
        var version = candidate.metadata.get("version");
        if (blank(version)) {
            issues.add(issue("MISSING_VERSION", "metadata has no version.",
                "Add a version to SKILL.md metadata, for example 1.0.0."));
        } else if (version.length() > 32 || !VERSION.matcher(version).matches()) {
            issues.add(issue("INVALID_VERSION", "version \"" + version + "\" is not a comparable version.",
                "Use MAJOR.MINOR.PATCH, for example 1.2.0."));
        }
        var author = candidate.metadata.get("author");
        if (blank(author)) {
            issues.add(issue("MISSING_AUTHOR", "metadata has no author.",
                "Add the author or team identifier to metadata."));
        } else {
            var authorName = author.trim();
            if (authorName.length() > 128 || CONTROL.matcher(authorName).matches()) {
                issues.add(issue("INVALID_AUTHOR", "author is empty, too long, or contains control characters.",
                    "Use 1-128 printable characters."));
            }
        }
        var tags = candidate.metadata.get("tags");
        if (blank(tags)) {
            issues.add(issue("MISSING_TAGS", "metadata has no tags.",
                "Add comma-separated tags to metadata, for example poster,image."));
        }
        if (!issues.isEmpty()) {
            return new SkillRuleResult(QualityIssueStatus.FAIL, issues);
        }
        return versionBump(candidate, version);
    }

    private SkillRuleResult versionBump(SkillPublishCandidate candidate, String version) {
        if (!candidate.contentChanged || candidate.previousVersion == null) return SkillRuleResult.pass();
        if (candidate.isRepoSource()) return SkillRuleResult.pass();
        if (SkillVersions.compare(version, candidate.previousVersion) > 0) return SkillRuleResult.pass();
        return SkillRuleResult.fail(SkillQualityIssues.issue(META, QualityIssueStatus.FAIL, "/metadata/version",
            "VERSION_NOT_BUMPED",
            "The skill content changed but the version did not increase (" + candidate.previousVersion + " -> " + version + ").",
            "Raise metadata.version above " + candidate.previousVersion + "."));
    }

    private StoredQualityIssue issue(String reason, String message, String suggestion) {
        return SkillQualityIssues.issue(META, QualityIssueStatus.FAIL,
            "/metadata", reason, message, suggestion);
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
