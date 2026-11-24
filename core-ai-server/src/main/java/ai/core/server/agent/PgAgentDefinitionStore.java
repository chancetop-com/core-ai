package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.DefinitionType;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.util.JsonSanitizer;
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
    private static final String SELECT_LIGHT = "SELECT (data - ?::text - ?::text - ?::text)::text AS data FROM " + TABLE;
    // list summaries need the catalog fields only; selecting data::text here detoasts every agent document.
    // json keys are bound as parameters: the framework rejects quoted literals in SQL
    private static final String SELECT_SUMMARY = "SELECT id, user_id, name, type, status, system_default, "
        + "data ->> ? AS published_name, data ->> ? AS description, "
        + "data ->> ? AS published_description, data ->> ? AS model FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, user_id, name, name_key, type, status, "
        + "system_default, forked_from, has_published_config, created_at, updated_at, data) ";

    private static AgentDefinition toDefinition(PgAgentDefinitionRow row) {
        return JsonUtil.fromJson(AgentDefinition.class, row.data);
    }

    private static AgentDefinition toSummary(PgAgentSummaryRow row) {
        var definition = new AgentDefinition();
        definition.id = row.id;
        definition.userId = row.userId;
        definition.name = row.name;
        definition.type = row.type != null ? DefinitionType.valueOf(row.type) : null;
        definition.status = row.status != null ? AgentStatus.valueOf(row.status) : null;
        definition.systemDefault = row.systemDefault;
        definition.description = row.description;
        definition.publishedName = row.publishedName;
        definition.publishedDescription = row.publishedDescription;
        definition.model = row.model;
        return definition;
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
        var sql = new StringBuilder(SELECT.length() + 128).append(request.summaryOnly ? SELECT_SUMMARY : SELECT);
        var params = new ArrayList<>();
        if (request.summaryOnly) {
            params.add("publishedName");
            params.add("description");
            params.add("publishedDescription");
            params.add("model");
        }
        conditions(request, sql, params);
        var sort = switch (request.sortField) {
            case "name_key" -> "name_key";
            case "created_at", "createdAt" -> "created_at";
            case "updated_at", "updatedAt" -> "updated_at";
            default -> throw new IllegalArgumentException("unsupported agent sort field: " + request.sortField);
        };
        var direction = request.ascending ? " ASC" : " DESC";
        sql.append(" ORDER BY ").append(sort).append(direction)
            .append(request.ascending ? " NULLS FIRST" : " NULLS LAST").append(", id ASC");
        if (request.limit != null && request.limit > 0) {
            sql.append(" LIMIT ?");
            params.add(request.limit);
        }
        sql.append(" OFFSET ?");
        params.add(Math.max(request.offset, 0));
        return request.summaryOnly
            ? database.select(sql.toString(), PgAgentSummaryRow.class, params.toArray())
                .stream().map(PgAgentDefinitionStore::toSummary).toList()
            : database.select(sql.toString(), PgAgentDefinitionRow.class, params.toArray())
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

    /**
     * Read projection for reference health: the system prompt is the largest field and is never
     * needed to resolve skill/tool refs, so it is stripped in SQL before the JSON is transferred.
     */
    private List<AgentDefinition> selectReferenceCandidates(String where, List<Object> whereParams, String orderAndLimit) {
        var params = new ArrayList<Object>();
        params.add("systemPrompt");
        params.add("inputTemplate");
        params.add("responseSchema");
        params.addAll(whereParams);
        var sql = SELECT_LIGHT + where + orderAndLimit;
        return database.select(sql, PgDocumentRow.class, params.toArray())
            .stream().map(row -> JsonUtil.fromJson(AgentDefinition.class, row.data)).toList();
    }

    @Override
    public List<AgentDefinition> findReferencingAgents(String skillId) {
        var json = JsonUtil.toJson(List.of(skillId));
        var params = new ArrayList<Object>();
        params.add("skillIds");
        params.add(json);
        params.add("publishedConfig");
        params.add("skillIds");
        params.add(json);
        return selectReferenceCandidates(
            " WHERE data -> ?::text @> ?::jsonb OR data -> ?::text -> ?::text @> ?::jsonb",
            params, "");
    }

    @Override
    public List<AgentDefinition> findAgentsWithApiRefs() {
        var apiRef = "[{\"type\":\"API\"}]";
        var params = new ArrayList<Object>();
        params.add("tools");
        params.add(apiRef);
        params.add("publishedConfig");
        params.add("tools");
        params.add(apiRef);
        return selectReferenceCandidates(
            " WHERE data -> ?::text @> ?::jsonb OR data -> ?::text -> ?::text @> ?::jsonb",
            params, "");
    }

    @Override
    public List<AgentDefinition> listReferenceCandidates(String afterId, int limit) {
        var params = new ArrayList<Object>();
        params.add(afterId == null ? "" : afterId);
        params.add("tools");
        params.add("skillIds");
        params.add("publishedConfig");
        params.add(Math.max(1, limit));
        return selectReferenceCandidates(
            " WHERE id > ? AND (jsonb_exists(data, ?::text) OR jsonb_exists(data, ?::text) OR jsonb_exists(data, ?::text))",
            params, " ORDER BY id ASC LIMIT ?");
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
            JsonSanitizer.json(JsonUtil.toJson(definition)), definition.id);
    }

    @Override
    public boolean replaceIfRevision(AgentDefinition definition, int expectedRevision) {
        definition.definitionRevision = expectedRevision + 1;
        definition.writeLeaseToken = null;
        definition.writeLeaseExpiresAt = null;
        return database.execute("UPDATE " + TABLE + " SET user_id = ?, name = ?, name_key = ?, type = ?, status = ?, "
                + "system_default = ?, forked_from = ?, has_published_config = ?, created_at = ?, updated_at = ?, "
                + "data = ?::jsonb WHERE id = ? AND COALESCE((data ->> ?)::int, 0) = ?",
            definition.userId, definition.name, definition.nameKey, definition.type != null ? definition.type.name() : null,
            definition.status != null ? definition.status.name() : null, definition.systemDefault, definition.forkedFrom,
            definition.publishedConfig != null, definition.createdAt, definition.updatedAt,
            JsonSanitizer.json(JsonUtil.toJson(definition)), definition.id, "definitionRevision", expectedRevision) > 0;
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
            JsonSanitizer.json(JsonUtil.toJson(definition))
        };
    }
}

