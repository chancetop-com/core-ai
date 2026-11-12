package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentComparison;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface AgentComparisonStore {
    Optional<AgentComparison> get(String id);

    void insert(AgentComparison comparison);

    void replace(AgentComparison comparison);

    void delete(String id);

    List<AgentComparison> find(AgentComparisonQuery query);

    Optional<AgentComparison> findOne(AgentComparisonQuery query);
}
