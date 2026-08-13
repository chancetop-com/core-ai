package ai.core.api.server.memory;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListAgentMemoriesResponse {
    @Property(name = "memories")
    public List<AgentMemoryView> memories;
}
