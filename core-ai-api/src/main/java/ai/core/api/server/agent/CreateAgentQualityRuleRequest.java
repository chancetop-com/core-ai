package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;
import java.util.Map;

/**
 * @author core-ai
 */
public class CreateAgentQualityRuleRequest {
    @NotNull
    @Property(name = "key")
    public String key;

    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "category")
    public String category;

    @Property(name = "description")
    public String description;

    @Property(name = "rationale")
    public String rationale;

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

    @Property(name = "parameters")
    public Map<String, String> parameters;
}
