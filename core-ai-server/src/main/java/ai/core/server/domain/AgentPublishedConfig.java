package ai.core.server.domain;

import core.framework.mongo.Field;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentPublishedConfig {
    @Field(name = "system_prompt")
    public String systemPrompt;

    @Field(name = "model")
    public String model;

    @Field(name = "temperature")
    public Double temperature;

    @Field(name = "max_turns")
    public Integer maxTurns;

    @Field(name = "timeout_seconds")
    public Integer timeoutSeconds;

    @Field(name = "tool_ids")
    public List<String> toolIds;

    @Field(name = "input_template")
    public String inputTemplate;

    @Field(name = "variables")
    public Map<String, String> variables;

    @Field(name = "response_schema")
    public String responseSchema;
}
