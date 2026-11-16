package ai.core.server.skill.quality;

import ai.core.server.quality.domain.QualityGateResult;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.server.quality.domain.StoredRuleResult;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Wire/storage shape of one skill quality run. It is intentionally flat and uses only public fields so the
 * existing JSON serializer can write it without a second DTO.
 *
 * @author core-ai
 */
public class SkillQualityReport {
    public static SkillQualityReport of(SkillPublishCandidate candidate, SkillQualityResult result) {
        var report = new SkillQualityReport();
        report.runId = result.runId();
        report.qualifiedName = candidate.qualifiedName();
        report.skillId = candidate.skillId;
        report.candidateHash = candidate.packageDigest;
        report.metadataHash = candidate.metadataHash;
        report.gateResult = result.gateResult();
        report.checksComplete = result.checksComplete();
        report.warnCount = result.warnCount();
        report.issues = result.issues();
        report.ruleResults = result.ruleResults();
        return report;
    }

    public String runId;
    public String subjectType = "SKILL";
    public String qualifiedName;
    public String skillId;
    public String candidateHash;
    public String metadataHash;
    public int policyRevision = SkillQualityGate.POLICY_REVISION;
    public QualityGateResult gateResult;
    public boolean checksComplete;
    public int warnCount;
    public List<StoredQualityIssue> issues;
    public List<StoredRuleResult> ruleResults;
    public ZonedDateTime createdAt = ZonedDateTime.now();
}
