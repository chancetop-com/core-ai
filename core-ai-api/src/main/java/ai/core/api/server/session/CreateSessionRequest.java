package ai.core.api.server.session;

import ai.core.api.server.tool.ToolRefView;
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

    @Property(name = "tools")
    public List<ToolRefView> tools;

    @Property(name = "skill_ids")
    public List<String> skillIds;

    @Property(name = "sub_agent_ids")
    public List<String> subAgentIds;
}
