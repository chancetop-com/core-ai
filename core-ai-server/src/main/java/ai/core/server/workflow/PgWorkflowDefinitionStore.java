package ai.core.server.workflow;

import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgWorkflowDefinitionStore implements WorkflowDefinitionStore {
    private static final String TABLE = "app.workflow_definitions";
    private static final String SELECT = "SELECT id, user_id, name, mode, draft_graph, visibility, status, "
        + "published_version_id, published_version, created_at, updated_at, archived_at, archived_by, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, user_id, name, mode, draft_graph, visibility, "
        + "status, published_version_id, published_version, created_at, updated_at, archived_at, archived_by, data) ";

    private static WorkflowDefinition toDefinition(PgWorkflowDefinitionRow row) {
        var definition = JsonUtil.fromJson(WorkflowDefinition.class, row.data);
        definition.id = row.id;
        definition.userId = row.userId;
        definition.name = row.name;
        definition.mode = row.mode != null ? ai.core.server.domain.WorkflowMode.valueOf(row.mode) : null;
        definition.draftGraph = row.draftGraph;
        definition.visibility = row.visibility != null ? ai.core.server.domain.WorkflowVisibility.valueOf(row.visibility) : null;
        definition.status = row.status != null ? ai.core.server.domain.WorkflowDefinitionStatus.valueOf(row.status) : null;
        definition.publishedVersionId = row.publishedVersionId;
        definition.publishedVersion = row.publishedVersion;
        definition.createdAt = row.createdAt;
        definition.updatedAt = row.updatedAt;
        definition.archivedAt = row.archivedAt;
        definition.archivedBy = row.archivedBy;
        return definition;
    }

    @Inject
    public Database database;

    @Override
    public Optional<WorkflowDefinition> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgWorkflowDefinitionRow.class, id)
            .map(PgWorkflowDefinitionStore::toDefinition);
    }

    @Override
    public void insert(WorkflowDefinition definition) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(definition));
    }

    @Override
    public void replace(WorkflowDefinition definition) {
        database.execute("UPDATE " + TABLE + " SET user_id = ?, name = ?, mode = ?, draft_graph = ?, visibility = ?, "
                + "status = ?, published_version_id = ?, published_version = ?, created_at = ?, updated_at = ?, "
                + "archived_at = ?, archived_by = ?, data = ?::jsonb WHERE id = ?",
            definition.userId, definition.name, definition.mode != null ? definition.mode.name() : null,
            JsonSanitizer.text(definition.draftGraph), definition.visibility != null ? definition.visibility.name() : null,
            definition.status != null ? definition.status.name() : null, definition.publishedVersionId,
            definition.publishedVersion, definition.createdAt, definition.updatedAt, definition.archivedAt,
            definition.archivedBy, JsonSanitizer.json(JsonUtil.toJson(definition)), definition.id);
    }

    @Override
    public void delete(String id) {
        database.execute("DELETE FROM " + TABLE + " WHERE id = ?", id);
    }

    @Override
    public List<WorkflowDefinition> listActive(String userId, Boolean myWorkflows, String keyword, int offset, int limit) {
        return list(activeWhere(userId, myWorkflows, keyword), offset, limit);
    }

    @Override
    public long countActive(String userId, Boolean myWorkflows, String keyword) {
        return count(activeWhere(userId, myWorkflows, keyword));
    }

    @Override
    public List<WorkflowDefinition> listArchived(String userId, String keyword, int offset, int limit) {
        return list(archivedWhere(userId, keyword), offset, limit);
    }

    @Override
    public long countArchived(String userId, String keyword) {
        return count(archivedWhere(userId, keyword));
    }

    @Override
    public List<WorkflowDefinition> explore(String userId, String keyword, int offset, int limit) {
        return list(activeWhere(userId, Boolean.FALSE, keyword), offset, limit);
    }

    @Override
    public long exploreCount(String userId, String keyword) {
        return count(activeWhere(userId, Boolean.FALSE, keyword));
    }

    @Override
    public List<WorkflowDefinition> findByIds(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        var placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return database.select(SELECT + " WHERE id IN (" + placeholders + ")", PgWorkflowDefinitionRow.class, ids.toArray())
            .stream().map(PgWorkflowDefinitionStore::toDefinition).toList();
    }

    @Override
    public List<WorkflowDefinition> listOwned(String userId) {
        return database.select(SELECT + " WHERE user_id = ?", PgWorkflowDefinitionRow.class, userId)
            .stream().map(PgWorkflowDefinitionStore::toDefinition).toList();
    }

    @Override
    public List<WorkflowDefinition> listPublicActive() {
        return database.select(SELECT + " WHERE visibility = ? AND status = ? AND published_version_id IS NOT NULL",
                PgWorkflowDefinitionRow.class, "PUBLIC", "ACTIVE")
            .stream().map(PgWorkflowDefinitionStore::toDefinition).toList();
    }

    private List<WorkflowDefinition> list(Where where, int offset, int limit) {
        var sql = SELECT + where.sql + " ORDER BY updated_at DESC NULLS LAST, id DESC LIMIT ? OFFSET ?";
        var params = new ArrayList<>(where.params);
        params.add(Math.min(Math.max(limit, 1), 100));
        params.add(Math.max(0, offset));
        return database.select(sql, PgWorkflowDefinitionRow.class, params.toArray())
            .stream().map(PgWorkflowDefinitionStore::toDefinition).toList();
    }

    private long count(Where where) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + where.sql,
                PgWorkflowDefinitionCountRow.class, where.params.toArray()).map(row -> row.total).orElse(0L);
    }

    private Where activeWhere(String userId, Boolean myWorkflows, String keyword) {
        var where = new ArrayList<String>();
        var params = new ArrayList<>();
        if (myWorkflows != null && !myWorkflows) {
            where.add("user_id <> ?");
            params.add(userId);
            where.add("published_version_id IS NOT NULL");
            where.add("(visibility = ? OR visibility IS NULL)");
            params.add("PUBLIC");
        } else {
            where.add("user_id = ?");
            params.add(userId);
        }
        where.add("(status IS NULL OR status <> ?)");
        params.add("ARCHIVED");
        where.add("(status IS NULL OR status <> ?)");
        params.add("DISABLED");
        addKeyword(where, params, keyword);
        return new Where(" WHERE " + String.join(" AND ", where), params);
    }

    private Where archivedWhere(String userId, String keyword) {
        var where = new ArrayList<String>();
        var params = new ArrayList<>();
        where.add("user_id = ?");
        params.add(userId);
        where.add("status = ?");
        params.add("ARCHIVED");
        addKeyword(where, params, keyword);
        return new Where(" WHERE " + String.join(" AND ", where), params);
    }

    private void addKeyword(List<String> where, List<Object> params, String keyword) {
        if (keyword != null && !keyword.isBlank()) {
            where.add("name ILIKE ?");
            params.add("%" + keyword + "%");
        }
    }

    private Object[] params(WorkflowDefinition definition) {
        return new Object[]{
            definition.id,
            definition.userId,
            definition.name,
            definition.mode != null ? definition.mode.name() : null,
            JsonSanitizer.text(definition.draftGraph),
            definition.visibility != null ? definition.visibility.name() : null,
            definition.status != null ? definition.status.name() : null,
            definition.publishedVersionId,
            definition.publishedVersion,
            definition.createdAt,
            definition.updatedAt,
            definition.archivedAt,
            definition.archivedBy,
            JsonSanitizer.json(JsonUtil.toJson(definition))
        };
    }

    private record Where(String sql, List<Object> params) {
    }
}


