package ai.core.api.server.session;

import ai.core.api.server.tool.ToolRefView;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author stephen
 */
public class SessionInfo {
    @NotNull
    @Property(name = "id")
    public String id;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "loaded_tools")
    public List<ToolRefView> loadedTools;

    @Property(name = "loaded_skill_ids")
    public List<String> loadedSkillIds;

    @Property(name = "loaded_sub_agent_ids")
    public List<String> loadedSubAgentIds;
}
