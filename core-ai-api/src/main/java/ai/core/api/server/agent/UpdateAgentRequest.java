package ai.core.api.server.agent;

import ai.core.api.apidefinition.ApiDefinitionType;
import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class UpdateAgentRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "system_prompt")
    public String systemPrompt;

    @Property(name = "system_prompt_id")
    public String systemPromptId;

    @Property(name = "model")
    public String model;

    @Property(name = "temperature")
    public Double temperature;

    @Property(name = "max_turns")
    public Integer maxTurns;

    @Property(name = "timeout_seconds")
    public Integer timeoutSeconds;

    @Property(name = "tool_ids")
    public List<String> toolIds;

    @Property(name = "input_template")
    public String inputTemplate;

    @Property(name = "variables")
    public Map<String, String> variables;

    @Property(name = "response_schema")
    public List<ApiDefinitionType> responseSchema;

    @Property(name = "type")
    public String type;
}
