package ai.core.server.quality.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;

/**
 * Append-only publish audit. Quality reports may expire; this record must still prove which gate decision
 * allowed each publish.
 *
 * @author core-ai
 */
@Collection(name = "agent_publication_records")
public class AgentPublicationRecord {
    @Id
    public String id;

    @Field(name = "agent_id")
    public String agentId;

    @Field(name = "definition_revision")
    public Integer definitionRevision;

    @Field(name = "run_id")
    public String runId;

    @Field(name = "execution_candidate_hash")
    public String executionCandidateHash;

    @Field(name = "metadata_hash")
    public String metadataHash;

    @Field(name = "policy_revision")
    public Integer policyRevision;

    @Field(name = "route_fingerprint")
    public String routeFingerprint;

    @Field(name = "gate_result")
    public QualityGateResult gateResult;

    @Field(name = "rule_result_digest")
    public String ruleResultDigest;

    @Field(name = "warn_count")
    public Integer warnCount;

    @Field(name = "operation_key_hash")
    public String operationKeyHash;

    @Field(name = "published_by")
    public String publishedBy;

    @Field(name = "published_at")
    public ZonedDateTime publishedAt;
}
