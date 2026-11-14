package ai.core.server.quality.domain;

import core.framework.mongo.Field;

/**
 * Persisted issue. It keeps the enforcement under which the result was produced because the same rule can
 * be evaluated as SHADOW for one run and BLOCK for the next; the report must not reinterpret old results.
 *
 * @author core-ai
 */
public class StoredQualityIssue {
    @Field(name = "rule_id")
    public String ruleId;

    @Field(name = "rule_revision")
    public Integer ruleRevision;

    @Field(name = "rule_name")
    public String ruleName;

    @Field(name = "rule_requirement")
    public String ruleRequirement;

    @Field(name = "status")
    public QualityIssueStatus status;

    @Field(name = "enforcement")
    public QualityEnforcement enforcement;

    @Field(name = "field_path")
    public String fieldPath;

    @Field(name = "evidence_kind")
    public QualityEvidenceKind evidenceKind;

    @Field(name = "evidence_text")
    public String evidenceText;

    @Field(name = "location_precision")
    public String locationPrecision;

    @Field(name = "message")
    public String message;

    @Field(name = "suggestion")
    public String suggestion;

    @Field(name = "decision_type")
    public String decisionType;

    @Field(name = "question_id")
    public String questionId;

    @Field(name = "predicate")
    public String predicate;

    @Field(name = "polarity")
    public String polarity;

    @Field(name = "decision_metric")
    public String decisionMetric;

    @Field(name = "affirmative_probability")
    public Double affirmativeProbability;

    @Field(name = "affirmative_threshold")
    public Double affirmativeThreshold;

    @Field(name = "negative_threshold")
    public Double negativeThreshold;

    @Field(name = "decision_mapping")
    public String decisionMapping;

    @Field(name = "issue_reason")
    public String issueReason;

    @Field(name = "retryable")
    public Boolean retryable;

    @Field(name = "recommended_action")
    public QualityRecommendedAction recommendedAction;

    @Field(name = "actual_count")
    public Integer actualCount;

    @Field(name = "max_count")
    public Integer maxCount;

    @Field(name = "category")
    public String category;

    @Field(name = "resource_type")
    public String resourceType;

    @Field(name = "resource_id")
    public String resourceId;

    @Field(name = "subagent_id")
    public String subagentId;
}
