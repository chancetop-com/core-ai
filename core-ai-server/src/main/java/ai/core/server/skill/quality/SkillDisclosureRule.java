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
        for (var broken : resolution.brokenLinks()) {
            if (status == QualityIssueStatus.INDETERMINATE) continue;
            status = QualityIssueStatus.FAIL;
            issues.add(issue(status, "/SKILL.md", "REFERENCE_NOT_PACKAGED",
                "链接目标未随本次上传发布：" + broken,
                "把该文件加入 skill 目录后重新发布，或移除该链接。"));
        }
        for (var requirement : SkillDisclosureRequirements.ALL) {
            if (resolution.sections().containsKey(requirement.key())) continue;
            if (resolution.tooLarge().containsKey(requirement.key())) {
                if (status != QualityIssueStatus.FAIL) status = QualityIssueStatus.INDETERMINATE;
                issues.add(issue(QualityIssueStatus.INDETERMINATE, "/SKILL.md", "SECTION_TOO_LARGE",
                    resolution.tooLarge().get(requirement.key()),
                    "添加对应子标题，或把 reference 拆分到 8000 字符以内。"));
                continue;
            }
            if (resolution.remoteRequirements().contains(requirement.key())) {
                status = QualityIssueStatus.FAIL;
                issues.add(issue(status, "/SKILL.md", "REMOTE_SECTION_NOT_ALLOWED",
                    requirement.label() + " 只指向外部 URL，发布时无法保证 Agent 可访问。",
                    "把内容放入 packaged reference，并从 SKILL.md 链接它。"));
                continue;
            }
            if (status != QualityIssueStatus.INDETERMINATE) status = QualityIssueStatus.FAIL;
            issues.add(issue(QualityIssueStatus.FAIL, "/SKILL.md", "MISSING_SECTION",
                "未找到 " + requirement.label() + " section，且没有可达的 packaged reference 提供它。",
                "添加 “## " + requirement.label() + "” section，或链接一个包含该 section 的 reference。"));
        }
        return new SkillRuleResult(status, issues);
    }

    private StoredQualityIssue issue(QualityIssueStatus status, String path, String reason,
                                     String message, String suggestion) {
        return SkillQualityIssues.issue(META, status, path, reason, message, suggestion);
    }
}
