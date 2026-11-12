package ai.core.server.memory;

import ai.core.server.memory.experiment.MemoryLayer;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgAgentMemoryStore implements AgentMemoryStore {
    private static final String TABLE = "app.agent_memories";
    private static final String SELECT = "SELECT id, agent_id, type, layer, content, created_at, updated_at, "
        + "data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, agent_id, type, layer, content, created_at, "
        + "updated_at, data) ";

    private static AgentMemory toMemory(PgAgentMemoryRow row) {
        var memory = JsonUtil.fromJson(AgentMemory.class, row.data);
        memory.id = row.id;
        memory.agentId = row.agentId;
        memory.type = row.type;
        memory.layer = layerOf(row.layer);
        memory.content = row.content;
        memory.createdAt = row.createdAt;
        memory.updatedAt = row.updatedAt;
        return memory;
    }

    private static MemoryLayer layerOf(String value) {
        if (value == null) return null;
        return switch (value) {
            case "knowledge" -> MemoryLayer.KNOWLEDGE;
            case "methods" -> MemoryLayer.METHODS;
            case "trajectories" -> MemoryLayer.TRAJECTORIES;
            default -> MemoryLayer.valueOf(value);
        };
    }

    private static AgentMemoryExtractionCursor toCursor(PgAgentMemoryCursorRow row) {
        var cursor = JsonUtil.fromJson(AgentMemoryExtractionCursor.class, row.data);
        cursor.id = row.id;
        cursor.agentId = row.agentId;
        return cursor;
    }

    @Inject
    public Database database;

    @Override
    public List<AgentMemory> findByAgentId(String agentId) {
        return database.select(SELECT + " WHERE agent_id = ? ORDER BY created_at DESC, id DESC",
                PgAgentMemoryRow.class, agentId)
            .stream().map(PgAgentMemoryStore::toMemory).toList();
    }

    @Override
    public List<AgentMemory> findByAgentIdAndLayer(String agentId, String layer) {
        return database.select(SELECT + " WHERE agent_id = ? AND layer = ? ORDER BY created_at ASC, id ASC",
                PgAgentMemoryRow.class, agentId, layer)
            .stream().map(PgAgentMemoryStore::toMemory).toList();
    }

    @Override
    public List<String> findAgentIdsWithMemories() {
        return database.select("SELECT DISTINCT agent_id FROM " + TABLE + " WHERE agent_id IS NOT NULL "
                + "AND agent_id <> ? ORDER BY agent_id", PgAgentMemoryAgentRow.class, "")
            .stream().map(row -> row.agentId).toList();
    }

    @Override
    public Optional<AgentMemory> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgAgentMemoryRow.class, id)
            .map(PgAgentMemoryStore::toMemory);
    }

    @Override
    public void insert(AgentMemory memory) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(memory));
    }

    @Override
    public boolean delete(String id) {
        return database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id) > 0;
    }

    @Override
    public long deleteByAgentId(String agentId) {
        return database.execute("DELETE FROM " + TABLE + " WHERE agent_id = ?", agentId);
    }

    @Override
    public long deleteLayerBefore(String agentId, String layer, ZonedDateTime cutoff) {
        return database.execute("DELETE FROM " + TABLE + " WHERE agent_id = ? AND layer = ? AND created_at < ?",
            agentId, layer, cutoff);
    }

    @Override
    public List<AgentMemory> search(String agentId, String keyword, String layer, int limit) {
        var where = " WHERE agent_id = ?"
            + (layer != null ? " AND layer = ?" : " AND layer IN (?, ?)")
            + " AND content ILIKE ? ORDER BY created_at DESC, id DESC LIMIT ?";
        var params = new ArrayList<>();
        params.add(agentId);
        if (layer != null) {
            params.add(layer);
        } else {
            params.add(MemoryLayer.METHODS.mongoValue());
            params.add(MemoryLayer.TRAJECTORIES.mongoValue());
        }
        params.add("%" + keyword + "%");
        params.add(limit);
        return database.select(SELECT + where, PgAgentMemoryRow.class, params.toArray())
            .stream().map(PgAgentMemoryStore::toMemory).toList();
    }

    @Override
    public AgentMemoryExtractionCursor getCursor(String agentId) {
        return database.selectOne("SELECT id, agent_id, data::text AS data FROM app.agent_memory_extraction_cursors "
                + "WHERE agent_id = ?", PgAgentMemoryCursorRow.class, agentId)
            .map(PgAgentMemoryStore::toCursor).orElse(null);
    }

    @Override
    public void saveCursor(AgentMemoryExtractionCursor cursor) {
        database.execute("INSERT INTO app.agent_memory_extraction_cursors (id, agent_id, data, updated_at) "
                + "VALUES (?, ?, ?::jsonb, ?) ON CONFLICT (agent_id) DO UPDATE SET id = EXCLUDED.id, "
                + "data = EXCLUDED.data, updated_at = EXCLUDED.updated_at",
            cursor.id, cursor.agentId, JsonSanitizer.json(JsonUtil.toJson(cursor)), cursor.updatedAt);
    }

    private Object[] params(AgentMemory memory) {
        return new Object[]{
            memory.id,
            memory.agentId,
            memory.type,
            memory.layer != null ? memory.layer.mongoValue() : "methods",
            JsonSanitizer.text(memory.content),
            memory.createdAt,
            memory.updatedAt,
            JsonSanitizer.json(JsonUtil.toJson(memory))
        };
    }
}


