package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityIssueStatus;
import ai.core.server.quality.domain.StoredQualityIssue;

import java.util.List;

/**
 * @author core-ai
 */
public record SkillRuleResult(QualityIssueStatus status, List<StoredQualityIssue> issues) {
    public SkillRuleResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    public static SkillRuleResult pass() {
        return new SkillRuleResult(QualityIssueStatus.PASS, List.of());
    }

    public static SkillRuleResult fail(StoredQualityIssue issue) {
        return new SkillRuleResult(QualityIssueStatus.FAIL, List.of(issue));
    }

    public static SkillRuleResult indeterminate(StoredQualityIssue issue) {
        return new SkillRuleResult(QualityIssueStatus.INDETERMINATE, List.of(issue));
    }

    public static SkillRuleResult warn(StoredQualityIssue issue) {
        return new SkillRuleResult(QualityIssueStatus.PASS, List.of(issue));
    }
}
