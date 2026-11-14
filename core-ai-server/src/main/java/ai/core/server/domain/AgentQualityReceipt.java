package ai.core.server.domain;

import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * Publish receipt stored inside the Agent document, written by the same conditional update that changes
 * the published config. Its presence with a matching candidate hash is what makes the publish auditable.
 *
 * @author core-ai
 */
public class AgentQualityReceipt {
    @Field(name = "run_id")
    public String runId;

    @Field(name = "policy_revision")
    public Integer policyRevision;

    @Field(name = "definition_revision")
    public Integer definitionRevision;

    @Field(name = "execution_candidate_hash")
    public String executionCandidateHash;

    @Field(name = "metadata_hash")
    public String metadataHash;

    @Field(name = "gate_result")
    public String gateResult;

    @Field(name = "rule_result_digest")
    public String ruleResultDigest;

    @Field(name = "route_fingerprint")
    public String routeFingerprint;

    @Field(name = "warn_count")
    public Integer warnCount;

    @Field(name = "published_at")
    public ZonedDateTime publishedAt;
}
