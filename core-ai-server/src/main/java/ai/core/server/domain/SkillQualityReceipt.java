package ai.core.server.domain;

import ai.core.server.quality.domain.QualityGateResult;
import core.framework.mongo.Field;

import java.time.ZonedDateTime;

/**
 * The gate result pinned to the exact package digest that was written. A later report may expire; this
 * receipt stays with the skill so "this revision was checked" remains provable.
 *
 * @author core-ai
 */
public class SkillQualityReceipt {
    @Field(name = "run_id")
    public String runId;

    @Field(name = "policy_revision")
    public Integer policyRevision;

    @Field(name = "revision")
    public Integer revision;

    @Field(name = "package_digest")
    public String packageDigest;

    @Field(name = "metadata_hash")
    public String metadataHash;

    @Field(name = "gate_result")
    public QualityGateResult gateResult;

    @Field(name = "exempted")
    public Boolean exempted;

    @Field(name = "exemption_reason")
    public String exemptionReason;

    @Field(name = "checks_complete")
    public Boolean checksComplete;

    @Field(name = "warn_count")
    public Integer warnCount;

    @Field(name = "rule_result_digest")
    public String ruleResultDigest;

    @Field(name = "checked_at")
    public ZonedDateTime checkedAt;
}
