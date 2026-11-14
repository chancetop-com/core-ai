package ai.core.server.quality.domain;

import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Immutable rule definition. A report or receipt records the revision it ran, so later edits never change
 * the meaning of history.
 *
 * @author core-ai
 */
@Collection(name = "agent_quality_rule_revisions")
public class AgentQualityRuleRevision {
    @Id
    public String id;

    @Field(name = "rule_id")
    public String ruleId;

    @Field(name = "revision")
    public Integer revision;

    @Field(name = "name")
    public String name;

    @Field(name = "category")
    public String category;

    @Field(name = "description")
    public String description;

    @Field(name = "rationale")
    public String rationale;

    @Field(name = "kind")
    public QualityRuleKind kind;

    @Field(name = "evaluator_key")
    public String evaluatorKey;

    @Field(name = "evaluator_version")
    public Integer evaluatorVersion;

    @Field(name = "parameters")
    public Map<String, String> parameters;

    @Field(name = "input_selector")
    public List<String> inputSelector;

    @Field(name = "decision_type")
    public String decisionType;

    @Field(name = "decision_question")
    public String decisionQuestion;

    @Field(name = "decision_instruction")
    public String decisionInstruction;

    @Field(name = "decision_criteria")
    public Map<String, String> decisionCriteria;

    @Field(name = "decision_levels")
    public List<String> decisionLevels;

    @Field(name = "decision_polarity")
    public String decisionPolarity;

    @Field(name = "affirmative_threshold")
    public Double affirmativeThreshold;

    @Field(name = "negative_threshold")
    public Double negativeThreshold;

    @Field(name = "model_alias")
    public String modelAlias;

    @Field(name = "expected_route_fingerprint")
    public String expectedRouteFingerprint;

    @Field(name = "message_template")
    public String messageTemplate;

    @Field(name = "remediation_template")
    public String remediationTemplate;

    @Field(name = "tested")
    public Boolean tested;

    @Field(name = "tested_at")
    public ZonedDateTime testedAt;

    @Field(name = "test_summary")
    public String testSummary;

    @Field(name = "change_reason")
    public String changeReason;

    @Field(name = "created_by")
    public String createdBy;

    @Field(name = "created_at")
    public ZonedDateTime createdAt;
}
