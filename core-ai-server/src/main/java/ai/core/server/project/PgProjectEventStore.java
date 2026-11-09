package ai.core.server.project;

import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
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
public class PgProjectEventStore implements ProjectEventStore {
    private static final String TABLE = "app.project_subject_events";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    @Inject
    Database database;

    private PgDocumentStore<ProjectSubjectEvent> store() {
        return new PgDocumentStore<>(database, TABLE, ProjectSubjectEvent.class, event -> event.id);
    }

    @Override
    public Optional<ZonedDateTime> latestAt(String subjectId) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ? ORDER BY (data ->> ?)::timestamptz DESC LIMIT 1",
                PgDocumentRow.class, "subjectId", subjectId, "at")
            .map(row -> toEvent(row).at);
    }

    @Override
    public long countByProjectAndSubject(String projectId, String subjectId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE data ->> ? = ? AND data ->> ? = ?",
                PgProjectCountRow.class, "projectId", projectId, "subjectId", subjectId)
            .map(row -> row.total).orElse(0L);
    }

    @Override
    public List<ProjectSubjectEvent> listByProjectAndSubject(String projectId, String subjectId, int offset, int limit) {
        return database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? ORDER BY (data ->> ?)::timestamptz LIMIT ? OFFSET ?",
                PgDocumentRow.class, "projectId", projectId, "subjectId", subjectId, "at", limit, offset)
            .stream().map(this::toEvent).toList();
    }

    @Override
    public List<ProjectSubjectEvent> listBySubjectAndType(String subjectId, String type, int limit) {
        return database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? ORDER BY (data ->> ?)::timestamptz DESC LIMIT ?",
                PgDocumentRow.class, "subjectId", subjectId, "type", type, "at", limit)
            .stream().map(this::toEvent).toList();
    }

    @Override
    public List<ProjectSubjectEvent> listByProject(String projectId, String subjectId, String type,
                                                    ZonedDateTime from, ZonedDateTime to, int limit) {
        var params = new ArrayList<Object>();
        var sql = new StringBuilder(SELECT.length() + 256).append(SELECT);
        var conditions = new ArrayList<String>();
        conditions.add("data ->> ? = ?");
        params.add("projectId");
        params.add(projectId);
        if (subjectId != null && !subjectId.isBlank()) {
            conditions.add("data ->> ? = ?");
            params.add("subjectId");
            params.add(subjectId);
        }
        if (type != null && !type.isBlank()) {
            conditions.add("data ->> ? = ?");
            params.add("type");
            params.add(type);
        }
        if (from != null) {
            conditions.add("(data ->> ?)::timestamptz >= ?");
            params.add("at");
            params.add(from);
        }
        if (to != null) {
            conditions.add("(data ->> ?)::timestamptz <= ?");
            params.add("at");
            params.add(to);
        }
        sql.append(" WHERE ").append(String.join(" AND ", conditions))
            .append(" ORDER BY (data ->> ?)::timestamptz DESC LIMIT ?");
        params.add("at");
        params.add(limit);
        return database.select(sql.toString(), PgDocumentRow.class, params.toArray())
            .stream().map(this::toEvent).toList();
    }

    @Override
    public boolean exists(String projectId, String subjectId, String type, String key, String value, ZonedDateTime at) {
        var params = new ArrayList<Object>();
        var conditions = new ArrayList<String>();
        conditions.add("data ->> ? = ?");
        params.add("projectId");
        params.add(projectId);
        conditions.add("data ->> ? = ?");
        params.add("subjectId");
        params.add(subjectId);
        conditions.add("(data ->> ?)::timestamptz = ?");
        params.add("at");
        params.add(at);
        conditions.add("data ->> ? = ?");
        params.add("type");
        params.add(type);
        conditions.add("data ->> ? = ?");
        params.add("value");
        params.add(value);
        if (key == null) {
            conditions.add("data ->> ? IS NULL");
            params.add("key");
        } else {
            conditions.add("data ->> ? = ?");
            params.add("key");
            params.add(key);
        }
        var sql = "SELECT count(*) AS total FROM " + TABLE + " WHERE " + String.join(" AND ", conditions);
        return database.selectOne(sql, PgProjectCountRow.class, params.toArray()).map(row -> row.total).orElse(0L) > 0;
    }

    @Override
    public void insert(ProjectSubjectEvent event) {
        store().insert(event);
    }

    @Override
    public void deleteBySubject(String subjectId) {
        database.execute("DELETE FROM " + TABLE + " WHERE data ->> ? = ?", "subjectId", subjectId);
    }

    private ProjectSubjectEvent toEvent(PgDocumentRow row) {
        return JsonUtil.fromJson(ProjectSubjectEvent.class, row.data);
    }
}
