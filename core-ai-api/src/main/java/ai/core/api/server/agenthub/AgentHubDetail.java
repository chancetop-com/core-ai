package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Detail of one hub agent: the search-result summary plus the extra hints a caller needs
 * before running it. {@code responseSchema} is only set for {@code llm_call} agents.
 *
 * @author stephen
 */
public class AgentHubDetail {
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
    public java.time.ZonedDateTime publishedAt;

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

    @Property(name = "input_hint")
    public String inputHint;

    @Property(name = "response_schema")
    public String responseSchema;
}
