package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Capability summary of one runnable agent, as exposed by the Agent Hub. It is deliberately
 * narrower than {@code AgentDefinitionView}: no system prompt, no model, no tool detail —
 * callers only see what they need to pick an agent.
 *
 * @author stephen
 */
public class AgentHubSummary {
    @Property(name = "id")
    public String id;

    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;

    @Property(name = "type")
    public String type;

    @Property(name = "source")
    public String source;

    @Property(name = "status")
    public String status;

    @Property(name = "published_at")
    public ZonedDateTime publishedAt;

    @Property(name = "owner_is_me")
    public Boolean ownerIsMe;

    @Property(name = "system_default")
    public Boolean systemDefault;

    @Property(name = "tool_count")
    public Integer toolCount;

    @Property(name = "skill_names")
    public List<String> skillNames;

    @Property(name = "sub_agent_names")
    public List<String> subAgentNames;

    @Property(name = "has_sandbox")
    public Boolean hasSandbox;
}
