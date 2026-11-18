package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Client-facing skill quality report. Snake-case properties keep it compatible with the shared
 * quality report renderer used by the agent publish flow.
 *
 * @author core-ai
 */
public class SkillQualityReportView {
    @Property(name = "run_id")
    public String runId;

    @Property(name = "subject_type")
    public String subjectType;

    @Property(name = "skill_id")
    public String skillId;

    @Property(name = "qualified_name")
    public String qualifiedName;

    @Property(name = "candidate_hash")
    public String candidateHash;

    @Property(name = "metadata_hash")
    public String metadataHash;

    @Property(name = "policy_revision")
    public Integer policyRevision;

    @Property(name = "gate_result")
    public String gateResult;

    @Property(name = "checks_complete")
    public Boolean checksComplete;

    @Property(name = "warn_count")
    public Integer warnCount;

    @Property(name = "block_fail_count")
    public Integer blockFailCount;

    @Property(name = "incomplete_count")
    public Integer incompleteCount;

    @Property(name = "rules_evaluated")
    public Integer rulesEvaluated;

    @Property(name = "rules_applicable")
    public Integer rulesApplicable;

    @Property(name = "issues")
    public List<SkillQualityIssueView> issues;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;
}
