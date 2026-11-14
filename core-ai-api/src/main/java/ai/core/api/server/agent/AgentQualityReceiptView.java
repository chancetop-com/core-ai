package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;

/**
 * Publish receipt summary. It proves which quality run allowed the currently published config and is the
 * authoritative "green light" identity together with the two hashes.
 *
 * @author core-ai
 */
public class AgentQualityReceiptView {
    @Property(name = "run_id")
    public String runId;

    @Property(name = "policy_revision")
    public Integer policyRevision;

    @Property(name = "definition_revision")
    public Integer definitionRevision;

    @Property(name = "execution_candidate_hash")
    public String executionCandidateHash;

    @Property(name = "metadata_hash")
    public String metadataHash;

    @Property(name = "gate_result")
    public String gateResult;

    @Property(name = "rule_result_digest")
    public String ruleResultDigest;

    @Property(name = "route_fingerprint")
    public String routeFingerprint;

    @Property(name = "warn_count")
    public Integer warnCount;

    @Property(name = "published_at")
    public ZonedDateTime publishedAt;
}
