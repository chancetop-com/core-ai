package ai.core.server.comparison;

import ai.core.server.comparison.domain.AgentVersion;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgAgentVersionStore implements AgentVersionStore {
    private static final String TABLE = "app.agent_versions";
    private static final String SELECT = "SELECT id, agent_id, agent_name, created_by, created_at, updated_at, "
        + "data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, agent_id, agent_name, created_by, "
        + "created_at, updated_at, data) ";

    private static AgentVersion toVersion(PgAgentVersionRow row) {
        return JsonUtil.fromJson(AgentVersion.class, row.data);
    }

    @Inject
    public Database database;

    @Override
    public Optional<AgentVersion> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgAgentVersionRow.class, id)
            .map(PgAgentVersionStore::toVersion);
    }

    @Override
    public List<AgentVersion> listByAgentId(String agentId, int limit) {
        return database.select(SELECT + " WHERE agent_id = ? ORDER BY created_at DESC, id ASC LIMIT ?",
                PgAgentVersionRow.class, agentId, limit)
            .stream().map(PgAgentVersionStore::toVersion).toList();
    }

    @Override
    public long countByAgentId(String agentId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE agent_id = ?",
                PgAgentVersionCountRow.class, agentId)
            .map(row -> row.total).orElse(0L);
    }

    @Override
    public void insert(AgentVersion version) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb)", params(version));
    }

    @Override
    public boolean insertIfAbsent(AgentVersion version) {
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
            params(version)) > 0;
    }

    @Override
    public void replace(AgentVersion version) {
        database.execute("UPDATE " + TABLE + " SET agent_id = ?, agent_name = ?, created_by = ?, created_at = ?, "
                + "updated_at = ?, data = ?::jsonb WHERE id = ?",
            version.agentId, version.agentName, version.createdBy, version.createdAt, version.updatedAt,
            JsonUtil.toJson(version), version.id);
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    private Object[] params(AgentVersion version) {
        return new Object[]{
            version.id,
            version.agentId,
            version.agentName,
            version.createdBy,
            version.createdAt,
            version.updatedAt,
            JsonUtil.toJson(version)
        };
    }
}
