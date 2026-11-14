package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Result of one quality run. Precheck and publish failures both return this shape; the publish error body
 * carries it as {@code quality_report} so old clients keep their {errorCode, message} envelope.
 *
 * @author core-ai
 */
public class AgentQualityReportView {
    @NotNull
    @Property(name = "run_id")
    public String runId;

    @NotNull
    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "definition_revision")
    public Integer definitionRevision;

    @Property(name = "execution_candidate_hash")
    public String executionCandidateHash;

    @Property(name = "metadata_hash")
    public String metadataHash;

    @Property(name = "policy_revision")
    public Integer policyRevision;

    @Property(name = "route_fingerprint")
    public String routeFingerprint;

    @NotNull
    @Property(name = "gate_result")
    public String gateResult;

    @Property(name = "checks_complete")
    public Boolean checksComplete;

    @Property(name = "run_status")
    public String runStatus;

    @Property(name = "mode")
    public String mode;

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
    public List<AgentQualityIssueView> issues;

    @Property(name = "started_at")
    public ZonedDateTime startedAt;

    @Property(name = "finished_at")
    public ZonedDateTime finishedAt;
}
