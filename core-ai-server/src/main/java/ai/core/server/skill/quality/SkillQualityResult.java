package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.server.quality.domain.StoredRuleResult;

import java.util.List;

/**
 * @author core-ai
 */
public record SkillQualityResult(String runId, QualityGateResult gateResult, boolean checksComplete,
                                 List<StoredQualityIssue> issues, List<StoredRuleResult> ruleResults,
                                 String routeFingerprint, String ruleResultDigest, int warnCount,
                                 int policyRevision, boolean exempted, String exemptionReason) {
    public SkillQualityResult {
        issues = issues == null ? List.of() : List.copyOf(issues);
        ruleResults = ruleResults == null ? List.of() : List.copyOf(ruleResults);
    }

    public boolean passed() {
        return gateResult == QualityGateResult.PASSED;
    }
}
