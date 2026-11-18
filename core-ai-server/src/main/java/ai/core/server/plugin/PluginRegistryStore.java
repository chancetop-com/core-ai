package ai.core.server.plugin;

import ai.core.server.domain.PluginRegistryEntry;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface PluginRegistryStore {
    Optional<PluginRegistryEntry> get(String id);

    List<PluginRegistryEntry> search(String query, int offset, int limit);

    long count(String query);

    void upsert(PluginRegistryEntry entry);

    void delete(String id);
}
