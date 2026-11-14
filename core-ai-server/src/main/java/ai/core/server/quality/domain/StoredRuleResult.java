package ai.core.server.quality.domain;

import core.framework.mongo.Field;

/**
 * Per-rule execution summary. The issue list is the actionable part; this exists so an admin can see which
 * rules ran, how long they took and which model route answered.
 *
 * @author core-ai
 */
public class StoredRuleResult {
    @Field(name = "rule_id")
    public String ruleId;

    @Field(name = "rule_revision")
    public Integer ruleRevision;

    @Field(name = "status")
    public QualityIssueStatus status;

    @Field(name = "enforcement")
    public QualityEnforcement enforcement;

    @Field(name = "duration_ms")
    public Long durationMs;

    @Field(name = "route_fingerprint")
    public String routeFingerprint;

    @Field(name = "error_reason")
    public String errorReason;
}
