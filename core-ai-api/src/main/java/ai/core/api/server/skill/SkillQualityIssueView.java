package ai.core.api.server.skill;

import core.framework.api.json.Property;

/**
 * One machine-readable skill quality issue. Text fields are rendered from the rule definition, never
 * from a model rationale.
 *
 * @author core-ai
 */
public class SkillQualityIssueView {
    @Property(name = "rule_id")
    public String ruleId;

    @Property(name = "rule_revision")
    public Integer ruleRevision;

    @Property(name = "rule_name")
    public String ruleName;

    @Property(name = "rule_requirement")
    public String ruleRequirement;

    @Property(name = "status")
    public String status;

    @Property(name = "enforcement")
    public String enforcement;

    @Property(name = "field_path")
    public String fieldPath;

    @Property(name = "evidence_kind")
    public String evidenceKind;

    @Property(name = "evidence_text")
    public String evidenceText;

    @Property(name = "location_precision")
    public String locationPrecision;

    @Property(name = "message")
    public String message;

    @Property(name = "suggestion")
    public String suggestion;

    @Property(name = "decision_type")
    public String decisionType;

    @Property(name = "question_id")
    public String questionId;

    @Property(name = "predicate")
    public String predicate;

    @Property(name = "polarity")
    public String polarity;

    @Property(name = "decision_metric")
    public String decisionMetric;

    @Property(name = "affirmative_probability")
    public Double affirmativeProbability;

    @Property(name = "affirmative_threshold")
    public Double affirmativeThreshold;

    @Property(name = "negative_threshold")
    public Double negativeThreshold;

    @Property(name = "decision_mapping")
    public String decisionMapping;

    @Property(name = "issue_reason")
    public String issueReason;

    @Property(name = "retryable")
    public Boolean retryable;

    @Property(name = "recommended_action")
    public String recommendedAction;

    @Property(name = "category")
    public String category;
}
