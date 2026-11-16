package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityEnforcement;
import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.skill.SkillResourceLimits;

import java.util.ArrayList;
import java.util.Locale;

/**
 * A skill package is transferred and unpacked into a sandbox, so path shape and size limits are enforced
 * before anything is written. Content policy (secrets, malware) is deliberately out of scope.
 *
 * @author core-ai
 */
public final class SkillPackageHygieneRule implements SkillQualityRule {
    private static final SkillRuleMeta META = new SkillRuleMeta(
        "SKILL.PACKAGE_HYGIENE", 1, "包卫生",
        "skill 包路径必须安全，文件数量与大小必须在上限内。", "PACKAGE",
        QualityEnforcement.BLOCK);
    private static final int MAX_FILES = 500;
    private static final long MAX_PACKAGE_BYTES = 64L * 1024 * 1024;

    @Override
    public SkillRuleMeta meta() {
        return META;
    }

    @Override
    public SkillRuleResult evaluate(SkillPublishCandidate candidate) {
        var issues = new ArrayList<ai.core.server.quality.domain.StoredQualityIssue>();
        if (candidate.resources.size() > MAX_FILES) {
            issues.add(issue("TOO_MANY_RESOURCES", "/resources",
                "资源文件数 " + candidate.resources.size() + " 超过上限 " + MAX_FILES + "。",
                "移除不必要的大文件或拆分 skill。"));
        }
        long total = 0;
        for (var entry : candidate.resources.entrySet()) {
            if ("skill.md".equals(entry.getKey().toLowerCase(Locale.ROOT))) {
                issues.add(issue("DUPLICATE_SKILL_MD", "/" + entry.getKey(),
                    "resources 中不能包含第二个 SKILL.md。", "把入口文件放在 workflow 的 skill_file 字段。"));
            }
            var size = entry.getValue() == null ? 0 : entry.getValue().length;
            total += size;
            if (size > SkillResourceLimits.MAX_RESOURCE_BYTES) {
                issues.add(issue("RESOURCE_TOO_LARGE", "/" + entry.getKey(),
                    "单文件超过 32MB：" + entry.getKey(), "压缩或移除该文件。"));
            }
        }
        if (total > MAX_PACKAGE_BYTES) {
            issues.add(issue("PACKAGE_TOO_LARGE", "/resources",
                "包总大小超过 64MB。", "缩减资源后再发布。"));
        }
        return issues.isEmpty() ? SkillRuleResult.pass()
            : new SkillRuleResult(QualityIssueStatus.FAIL, issues);
    }

    private ai.core.server.quality.domain.StoredQualityIssue issue(String reason, String path,
                                                                 String message, String suggestion) {
        return SkillQualityIssues.issue(META, QualityIssueStatus.FAIL, path, reason, message, suggestion);
    }
}
