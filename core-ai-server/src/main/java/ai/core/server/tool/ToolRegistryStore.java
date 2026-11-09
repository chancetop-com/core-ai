package ai.core.server.tool;

import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ToolRegistryStore {
    Optional<ToolRegistryEntry> get(String id);

    List<ToolRegistryEntry> listEnabledOrMcp();

    List<ToolRegistryEntry> listByType(ToolType type);

    Optional<ToolRegistryEntry> findMcpServerByName(String name);

    void insert(ToolRegistryEntry entry);

    void replace(ToolRegistryEntry entry);

    void delete(String id);
}
