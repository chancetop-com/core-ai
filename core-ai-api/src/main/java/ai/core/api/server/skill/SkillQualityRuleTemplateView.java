package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class SkillQualityRuleTemplateView {
    @Property(name = "evaluator_key")
    public String evaluatorKey;

    @Property(name = "name")
    public String name;

    @Property(name = "kind")
    public String kind;

    @Property(name = "category")
    public String category;

    @Property(name = "description")
    public String description;

    @Property(name = "parameters")
    public List<SkillQualityRuleParameterView> parameters;

    @Property(name = "input_options")
    public List<String> inputOptions;

    @Property(name = "supports_decision")
    public Boolean supportsDecision;
}
