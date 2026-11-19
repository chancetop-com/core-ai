package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * A decision question compiled into a built-in evaluator. Read-only: it documents what the rule asks,
 * it is not a configurable field.
 *
 * @author core-ai
 */
public class AgentQualityRuleQuestionView {
    @Property(name = "name")
    public String name;

    @Property(name = "type")
    public String type;

    @Property(name = "instruction")
    public String instruction;

    @Property(name = "criteria")
    public Map<String, String> criteria;
}
