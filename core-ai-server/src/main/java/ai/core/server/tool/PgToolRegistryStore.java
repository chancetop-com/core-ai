package ai.core.server.tool;

import ai.core.server.domain.ToolRegistryEntry;
import ai.core.server.domain.ToolType;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgToolRegistryStore implements ToolRegistryStore {
    private static final String TABLE = "app.tool_registry";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    private static ToolRegistryEntry toEntry(PgDocumentRow row) {
        return JsonUtil.fromJson(ToolRegistryEntry.class, row.data);
    }

    @Inject
    Database database;

    private PgDocumentStore<ToolRegistryEntry> store() {
        return new PgDocumentStore<>(database, TABLE, ToolRegistryEntry.class, entry -> entry.id);
    }

    @Override
    public Optional<ToolRegistryEntry> get(String id) {
        return store().get(id);
    }

    @Override
    public List<ToolRegistryEntry> listEnabledOrMcp() {
        return database.select(SELECT + " WHERE (data ->> ?)::boolean = true OR data ->> ? = ?",
                        PgDocumentRow.class, "enabled", "type", ToolType.MCP.name())
                .stream().map(PgToolRegistryStore::toEntry).toList();
    }

    @Override
    public List<ToolRegistryEntry> listByType(ToolType type) {
        return database.select(SELECT + " WHERE data ->> ? = ?", PgDocumentRow.class, "type", type.name())
                .stream().map(PgToolRegistryStore::toEntry).toList();
    }

    @Override
    public Optional<ToolRegistryEntry> findMcpServerByName(String name) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ?",
                        PgDocumentRow.class, "type", ToolType.MCP.name(), "name", name)
                .map(PgToolRegistryStore::toEntry);
    }

    @Override
    public void insert(ToolRegistryEntry entry) {
        store().insert(entry);
    }

    @Override
    public void replace(ToolRegistryEntry entry) {
        store().replace(entry);
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }
}
