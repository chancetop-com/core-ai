package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class AgentDefinitionView {
    @NotNull
    @Property(name = "id")
    public String id;

    @NotNull
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "system_prompt")
    public String systemPrompt;

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

    @Property(name = "webhook_secret")
    public String webhookSecret;

    @Property(name = "system_default")
    public Boolean systemDefault;

    @Property(name = "status")
    public String status;

    @Property(name = "published_at")
    public ZonedDateTime publishedAt;

    @Property(name = "created_at")
    public ZonedDateTime createdAt;

    @Property(name = "updated_at")
    public ZonedDateTime updatedAt;
}
