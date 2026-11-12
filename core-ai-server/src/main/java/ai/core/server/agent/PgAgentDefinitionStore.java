package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgAgentDefinitionStore implements AgentDefinitionStore {
    private static final String TABLE = "app.agents";
    private static final String SELECT = "SELECT id, user_id, name, name_key, type, status, system_default, "
        + "forked_from, has_published_config, created_at, updated_at, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, user_id, name, name_key, type, status, "
        + "system_default, forked_from, has_published_config, created_at, updated_at, data) ";

    private static AgentDefinition toDefinition(PgAgentDefinitionRow row) {
        return JsonUtil.fromJson(AgentDefinition.class, row.data);
    }

    @Inject
    public Database database;

    @Override
    public Optional<AgentDefinition> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgAgentDefinitionRow.class, id)
            .map(PgAgentDefinitionStore::toDefinition);
    }

    @Override
    public List<AgentDefinition> find(AgentDefinitionQuery request) {
        var sql = new StringBuilder(SELECT.length() + 128).append(SELECT);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        var sort = switch (request.sortField) {
            case "name_key" -> "name_key";
            case "created_at", "createdAt" -> "created_at";
            case "updated_at", "updatedAt" -> "updated_at";
            default -> throw new IllegalArgumentException("unsupported agent sort field: " + request.sortField);
        };
        var direction = request.ascending ? " ASC" : " DESC";
        var orderBy = " ORDER BY " + sort + direction + (request.ascending ? " NULLS FIRST" : " NULLS LAST")
            + ", id ASC LIMIT ? OFFSET ?";
        sql.append(orderBy);
        params.add(request.limit <= 0 ? 1000 : request.limit);
        params.add(Math.max(request.offset, 0));
        return database.select(sql.toString(), PgAgentDefinitionRow.class, params.toArray())
            .stream().map(PgAgentDefinitionStore::toDefinition).toList();
    }

    @Override
    public long count(AgentDefinitionQuery request) {
        var sql = new StringBuilder("SELECT count(*) AS total FROM ").append(TABLE);
        var params = new ArrayList<>();
        conditions(request, sql, params);
        return database.selectOne(sql.toString(), PgAgentCountRow.class, params.toArray())
            .map(row -> row.total).orElse(0L);
    }

    private void scopeConditions(AgentDefinitionQuery request, List<String> where, List<Object> params) {
        switch (request.scope) {
            case DIRECT -> {
                where.add("user_id = ?");
                params.add(request.userId);
            }
            case OWNED_OR_SYSTEM -> {
                where.add("(user_id = ? OR system_default = true)");
                params.add(request.userId);
            }
            case OWNED_NO_SYSTEM, WORKFLOW_MINE -> {
                where.add("user_id = ? AND system_default IS NOT TRUE");
                params.add(request.userId);
            }
            case SHARED -> {
                where.add("user_id <> ? AND system_default IS NOT TRUE AND forked_from IS NULL");
                params.add(request.userId);
            }
            case VISIBLE -> {
                where.add("(user_id = ? OR system_default = true OR (user_id <> ? AND system_default IS NOT TRUE "
                    + "AND forked_from IS NULL))");
                params.add(request.userId);
                params.add(request.userId);
            }
            case WORKFLOW_SHARED -> {
                where.add("status = ? AND has_published_config = true AND forked_from IS NULL "
                    + "AND (system_default = true OR user_id <> ?)");
                params.add("PUBLISHED");
                params.add(request.userId);
            }
            default -> {
            }
        }
    }

    private void conditions(AgentDefinitionQuery request, StringBuilder sql, List<Object> params) {
        var where = new ArrayList<String>();
        scopeConditions(request, where, params);
        if (request.forkVisibilityUserId != null) {
            where.add("(forked_from IS NULL OR user_id = ?)");
            params.add(request.forkVisibilityUserId);
        }
        addEqual(where, params, "type", request.type);
        addEqual(where, params, "status", request.status);
        addEqual(where, params, "name", request.name);
        addEqual(where, params, "name_key", request.nameKey);
        if (request.nameKeyPrefix != null) {
            where.add("name_key LIKE ?");
            params.add(request.nameKeyPrefix + "%");
        }
        if (request.systemDefault != null) {
            where.add("system_default = ?");
            params.add(request.systemDefault);
        }
        if (request.forkedFromIsNull != null) {
            where.add(request.forkedFromIsNull ? "forked_from IS NULL" : "forked_from IS NOT NULL");
        }
        if (request.hasPublishedConfig != null) {
            where.add(request.hasPublishedConfig ? "has_published_config = true" : "has_published_config = false");
        }
        if (request.ids != null) {
            if (request.ids.isEmpty()) {
                where.add("1 = 0");
            } else {
                where.add("id IN (" + placeholders(request.ids.size()) + ")");
                params.addAll(request.ids);
            }
        }
        if (request.excludeIds != null && !request.excludeIds.isEmpty()) {
            where.add("id NOT IN (" + placeholders(request.excludeIds.size()) + ")");
            params.addAll(request.excludeIds);
        }
        if (!where.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", where));
    }

    private void addEqual(List<String> where, List<Object> params, String column, String value) {
        if (value == null) return;
        where.add(column + " = ?");
        params.add(value);
    }

    private String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    @Override
    public void insert(AgentDefinition definition) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(definition));
    }

    @Override
    public void replace(AgentDefinition definition) {
        database.execute("UPDATE " + TABLE + " SET user_id = ?, name = ?, name_key = ?, type = ?, status = ?, "
                + "system_default = ?, forked_from = ?, has_published_config = ?, created_at = ?, updated_at = ?, "
                + "data = ?::jsonb WHERE id = ?",
            definition.userId, definition.name, definition.nameKey, definition.type != null ? definition.type.name() : null,
            definition.status != null ? definition.status.name() : null, definition.systemDefault, definition.forkedFrom,
            definition.publishedConfig != null, definition.createdAt, definition.updatedAt,
            JsonUtil.toJson(definition), definition.id);
    }

    @Override
    public void upsert(AgentDefinition definition) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) "
                + "ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, name = EXCLUDED.name, "
                + "name_key = EXCLUDED.name_key, type = EXCLUDED.type, status = EXCLUDED.status, "
                + "system_default = EXCLUDED.system_default, forked_from = EXCLUDED.forked_from, "
                + "has_published_config = EXCLUDED.has_published_config, created_at = EXCLUDED.created_at, "
                + "updated_at = EXCLUDED.updated_at, data = EXCLUDED.data",
            params(definition));
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    @Override
    public boolean insertIfAbsent(AgentDefinition definition) {
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb) ON CONFLICT DO NOTHING",
            params(definition)) > 0;
    }

    private Object[] params(AgentDefinition definition) {
        return new Object[]{
            definition.id,
            definition.userId,
            definition.name,
            definition.nameKey,
            definition.type != null ? definition.type.name() : null,
            definition.status != null ? definition.status.name() : null,
            definition.systemDefault,
            definition.forkedFrom,
            definition.publishedConfig != null,
            definition.createdAt,
            definition.updatedAt,
            JsonUtil.toJson(definition)
        };
    }
}
