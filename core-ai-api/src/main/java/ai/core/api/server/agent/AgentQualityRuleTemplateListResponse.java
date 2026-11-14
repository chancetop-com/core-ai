package ai.core.api.server.agent;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author core-ai
 */
public class AgentQualityRuleTemplateListResponse {
    @Property(name = "templates")
    public List<AgentQualityRuleTemplateView> templates;

    @Property(name = "input_options")
    public List<String> inputOptions;
}
