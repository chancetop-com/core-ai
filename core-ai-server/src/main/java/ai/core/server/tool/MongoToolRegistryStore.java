package ai.core.server.tool;

import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class MongoToolRegistryStore implements ToolRegistryStore {
    public static MongoToolRegistryStore of(MongoCollection<ToolRegistryEntry> collection) {
        var store = new MongoToolRegistryStore();
        store.collection = collection;
        return store;
    }

    @Inject
    MongoCollection<ToolRegistryEntry> collection;

    @Override
    public Optional<ToolRegistryEntry> get(String id) {
        return collection.get(id);
    }

    @Override
    public List<ToolRegistryEntry> listEnabledOrMcp() {
        return collection.find(Filters.or(
                Filters.eq("enabled", Boolean.TRUE),
                Filters.eq("type", ToolType.MCP.name())));
    }

    @Override
    public List<ToolRegistryEntry> listByType(ToolType type) {
        return collection.find(Filters.eq("type", type.name()));
    }

    @Override
    public Optional<ToolRegistryEntry> findMcpServerByName(String name) {
        return collection.findOne(Filters.and(
                Filters.eq("type", ToolType.MCP.name()),
                Filters.eq("name", name)));
    }

    @Override
    public void insert(ToolRegistryEntry entry) {
        collection.insert(entry);
    }

    @Override
    public void replace(ToolRegistryEntry entry) {
        collection.replace(entry);
    }

    @Override
    public void delete(String id) {
        collection.delete(id);
    }
}
