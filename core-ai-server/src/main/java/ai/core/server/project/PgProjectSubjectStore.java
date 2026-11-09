package ai.core.server.project;

import ai.core.server.domain.ProjectSubject;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class PgProjectSubjectStore implements ProjectSubjectStore {
    private static final String TABLE = "app.project_subjects";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    @Inject
    Database database;

    private PgDocumentStore<ProjectSubject> store() {
        return new PgDocumentStore<>(database, TABLE, ProjectSubject.class, subject -> subject.id);
    }

    @Override
    public Optional<ProjectSubject> get(String id) {
        return store().get(id);
    }

    @Override
    public List<ProjectSubject> listByProject(String projectId) {
        return database.select(SELECT + " WHERE data ->> ? = ? ORDER BY data ->> ?",
                PgDocumentRow.class, "projectId", projectId, "createdAt")
            .stream().map(this::toSubject).toList();
    }

    @Override
    public List<ProjectSubject> listStarted(String projectId) {
        return database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ?",
                PgDocumentRow.class, "projectId", projectId, "status", "started")
            .stream().map(this::toSubject).toList();
    }

    @Override
    public long countStarted(String projectId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE data ->> ? = ? AND data ->> ? = ?",
                PgProjectCountRow.class, "projectId", projectId, "status", "started")
            .map(row -> row.total).orElse(0L);
    }

    @Override
    public List<ProjectSubject> listWithReportRunId(int limit) {
        return database.select(SELECT + " WHERE (data ->> ?) IS NOT NULL LIMIT ?",
                PgDocumentRow.class, "reportRunId", limit)
            .stream().map(this::toSubject).toList();
    }

    @Override
    public void insert(ProjectSubject subject) {
        store().insert(subject);
    }

    @Override
    public boolean insertIfAbsent(ProjectSubject subject) {
        return database.execute("INSERT INTO " + TABLE + " (id, data, updated_at) VALUES (?, ?::jsonb, now()) "
            + "ON CONFLICT DO NOTHING", subject.id, JsonUtil.toJson(subject)) > 0;
    }

    @Override
    public Optional<ProjectSubject> findAutoByNameKey(String projectId, String nameKey) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?",
                PgDocumentRow.class, "projectId", projectId, "nameKey", nameKey, "source", "auto")
            .map(this::toSubject);
    }

    @Override
    public void update(String id, Consumer<ProjectSubject> mutator) {
        try (var transaction = database.beginTransaction()) {
            var row = database.selectOne(SELECT + " WHERE id = ? FOR UPDATE", PgDocumentRow.class, id).orElse(null);
            if (row == null) {
                transaction.commit();
                return;
            }
            var subject = toSubject(row);
            mutator.accept(subject);
            store().replace(subject);
            transaction.commit();
        }
    }

    @Override
    public void delete(String id) {
        store().delete(id);
    }

    private ProjectSubject toSubject(PgDocumentRow row) {
        return JsonUtil.fromJson(ProjectSubject.class, row.data);
    }
}
