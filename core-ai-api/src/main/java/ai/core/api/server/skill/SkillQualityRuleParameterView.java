package ai.core.api.server.skill;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class SkillQualityRuleParameterView {
    @Property(name = "name")
    public String name;

    @Property(name = "type")
    public String type;

    @Property(name = "required")
    public Boolean required;

    @Property(name = "description")
    public String description;

    @Property(name = "default_value")
    public String defaultValue;

    @Property(name = "options")
    public List<String> options;
}
