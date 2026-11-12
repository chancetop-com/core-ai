package ai.core.server.memory;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface AgentMemoryStore {
    List<AgentMemory> findByAgentId(String agentId);

    List<AgentMemory> findByAgentIdAndLayer(String agentId, String layer);

    List<String> findAgentIdsWithMemories();

    Optional<AgentMemory> get(String id);

    void insert(AgentMemory memory);

    boolean delete(String id);

    long deleteByAgentId(String agentId);

    long deleteLayerBefore(String agentId, String layer, ZonedDateTime cutoff);

    List<AgentMemory> search(String agentId, String keyword, String layer, int limit);

    AgentMemoryExtractionCursor getCursor(String agentId);

    void saveCursor(AgentMemoryExtractionCursor cursor);
}
