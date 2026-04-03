package ai.core.api.server.session;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class CreateSessionRequest {
    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "config")
    public SessionConfig config;

    @Property(name = "tool_ids")
    public List<String> toolIds;

    @Property(name = "skill_ids")
    public List<String> skillIds;
}
