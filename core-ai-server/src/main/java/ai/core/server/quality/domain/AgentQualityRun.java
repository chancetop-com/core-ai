package ai.core.server.quality.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * One quality evaluation. Runs are independent of the Agent's DRAFT/PUBLISHED status: a green light is
 * identified by the candidate hashes, not by "the agent is published".
 *
 * @author core-ai
 */
@Collection(name = "agent_quality_runs")
public class AgentQualityRun {
    @Id
    public String id;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "actor")
    public String actor;

    @Field(name = "mode")
    public QualityGateMode mode;

    @Field(name = "idempotency_key")
    public String idempotencyKey;

    @Field(name = "operation_key_hash")
    public String operationKeyHash;

    @Field(name = "definition_revision")
    public Integer definitionRevision;

    @Field(name = "execution_candidate_hash")
    public String executionCandidateHash;

    @Field(name = "metadata_hash")
    public String metadataHash;

    @Field(name = "dependency_fingerprint")
    public String dependencyFingerprint;

    @Field(name = "policy_revision")
    public Integer policyRevision;

    @Field(name = "run_status")
    public QualityRunStatus runStatus;

    @Field(name = "gate_result")
    public QualityGateResult gateResult;

    @Field(name = "checks_complete")
    public Boolean checksComplete;

    @Field(name = "issues")
    public List<StoredQualityIssue> issues;

    @Field(name = "rule_results")
    public List<StoredRuleResult> ruleResults;

    @Field(name = "route_fingerprint")
    public String routeFingerprint;

    @Field(name = "rule_result_digest")
    public String ruleResultDigest;

    @Field(name = "model_usage")
    public Map<String, String> modelUsage;

    @Field(name = "shadow_execution_error")
    public String shadowExecutionError;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @Field(name = "finished_at")
    public ZonedDateTime finishedAt;

    @Field(name = "expires_at")
    public ZonedDateTime expiresAt;
}
