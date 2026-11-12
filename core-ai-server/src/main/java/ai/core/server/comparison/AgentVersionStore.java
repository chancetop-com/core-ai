package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentVersion;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface AgentVersionStore {
    Optional<AgentVersion> get(String id);

    List<AgentVersion> listByAgentId(String agentId, int limit);

    long countByAgentId(String agentId);

    void insert(AgentVersion version);

    boolean insertIfAbsent(AgentVersion version);

    void replace(AgentVersion version);

    void delete(String id);
}
