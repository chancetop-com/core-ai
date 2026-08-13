package ai.core.api.server.systemprompt;

import core.framework.api.json.Property;

import java.util.Map;

/**
 * @author stephen
 */
public class SystemPromptTestRequest {
    @Property(name = "model")
    public String model;

    @Property(name = "userMessage")
    public String userMessage;

    @Property(name = "variables")
    public Map<String, String> variables;
}
