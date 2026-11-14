package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * Logical rule identity plus its current revision. Editing a rule creates a new immutable revision;
 * {@code revision} is what a published receipt records.
 *
 * @author core-ai
 */
public class AgentQualityRuleView {
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "key")
    public String key;

    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "category")
    public String category;

    @Property(name = "lifecycle")
    public String lifecycle;

    @Property(name = "revision")
    public Integer revision;

    @Property(name = "description")
    public String description;

    @Property(name = "rationale")
    public String rationale;

    @Property(name = "kind")
    public String kind;

    @Property(name = "evaluator_key")
    public String evaluatorKey;

    @Property(name = "evaluator_version")
    public Integer evaluatorVersion;

    @Property(name = "parameters")
    public Map<String, String> parameters;

    @Property(name = "input_selector")
    public List<String> inputSelector;

    @Property(name = "decision_type")
    public String decisionType;

    @Property(name = "decision_question")
    public String decisionQuestion;

    @Property(name = "decision_instruction")
    public String decisionInstruction;

    @Property(name = "decision_criteria")
    public Map<String, String> decisionCriteria;

    @Property(name = "decision_levels")
    public List<String> decisionLevels;

    @Property(name = "decision_polarity")
    public String decisionPolarity;

    @Property(name = "affirmative_threshold")
    public Double affirmativeThreshold;

    @Property(name = "negative_threshold")
    public Double negativeThreshold;

    @Property(name = "model_alias")
    public String modelAlias;

    @Property(name = "expected_route_fingerprint")
    public String expectedRouteFingerprint;

    @Property(name = "message_template")
    public String messageTemplate;

    @Property(name = "remediation_template")
    public String remediationTemplate;

    @Property(name = "enforcement")
    public String enforcement;

    @Property(name = "built_in")
    public Boolean builtIn;

    @Property(name = "editable_parameters")
    public List<String> editableParameters;

    @Property(name = "tested")
    public Boolean tested;

    @Property(name = "tested_at")
    public ZonedDateTime testedAt;

    @Property(name = "test_summary")
    public String testSummary;

    @Property(name = "updated_by")
    public String updatedBy;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;

    @Property(name = "change_reason")
    public String changeReason;
}
