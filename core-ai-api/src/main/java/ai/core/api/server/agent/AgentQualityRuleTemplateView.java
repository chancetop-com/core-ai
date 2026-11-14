package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class AgentQualityRuleTemplateView {
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
    public List<AgentQualityRuleParameterView> parameters;

    @Property(name = "input_options")
    public List<String> inputOptions;

    @Property(name = "supports_decision")
    public Boolean supportsDecision;
}
