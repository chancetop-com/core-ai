package ai.core.server.domain;

import core.framework.api.validate.NotNull;
import core.framework.mongo.Collection;
import core.framework.mongo.Field;
import core.framework.mongo.Id;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
@Collection(name = "agents")
public class AgentDefinition {
    @Id
    public String id;

    @NotNull
    @Field(name = "user_id")
    public String userId;

    @NotNull
    @Field(name = "name")
    public String name;

    @Field(name = "description")
    public String description;

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

    @Field(name = "tools")
    public List<ToolRef> tools;

    @Field(name = "skill_ids")
    public List<String> skillIds;

    @Field(name = "subagent_ids")
    public List<String> subAgentIds;

    @Field(name = "input_template")
    public String inputTemplate;

    @Field(name = "variables")
    public Map<String, String> variables;

    @Field(name = "system_prompt_id")
    public String systemPromptId;

    @Field(name = "webhook_secret")
    public String webhookSecret;

    @Field(name = "system_default")
    public Boolean systemDefault;

    @NotNull
    @Field(name = "type")
    public DefinitionType type;

    @Field(name = "response_schema")
    public String responseSchema;

    @NotNull
    @Field(name = "status")
    public AgentStatus status;

    @Field(name = "published_config")
    public AgentPublishedConfig publishedConfig;

    @Field(name = "sandbox_config")
    public AgentSandboxConfig sandboxConfig;

    @Field(name = "published_at")
    public ZonedDateTime publishedAt;

    @NotNull
    @Field(name = "created_at")
    public ZonedDateTime createdAt;

    @NotNull
    @Field(name = "updated_at")
    public ZonedDateTime updatedAt;
}
