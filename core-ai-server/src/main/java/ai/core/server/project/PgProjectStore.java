package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * PostgreSQL project store. Mutations lock the row, apply the mutator and write the full document;
 * concurrent analysis cursor/claim writes are serialized instead of being clobbered.
 *
 * @author stephen
 */
public class PgProjectStore implements ProjectStore {
    private static final String TABLE = "app.projects";
    private static final String SELECT = "SELECT id, user_id, status, stats_dirty, analysis_status, last_analyzed_at, "
            + "last_analysis_at, created_at, updated_at, archived_at, data::text AS data FROM " + TABLE;

    private static Project toProject(PgProjectRow row) {
        return JsonUtil.fromJson(Project.class, row.data);
    }

    @Inject
    Database database;

    @Override
    public Optional<Project> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgProjectRow.class, id).map(PgProjectStore::toProject);
    }

    @Override
    public void insert(Project project) {
        database.execute("INSERT INTO " + TABLE + " (id, user_id, status, stats_dirty, analysis_status, "
                        + "last_analyzed_at, last_analysis_at, created_at, updated_at, archived_at, data) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)",
                project.id, project.userId, project.status, project.statsDirty, project.analysisStatus,
                project.lastAnalyzedAt, project.lastAnalysisAt, project.createdAt, project.updatedAt, project.archivedAt,
                JsonUtil.toJson(project));
    }

    @Override
    public boolean update(String id, Consumer<Project> mutator) {
        return updateIf(id, project -> true, mutator);
    }

    @Override
    public boolean updateIf(String id, Predicate<Project> guard, Consumer<Project> mutator) {
        try (var transaction = database.beginTransaction()) {
            var row = database.selectOne(SELECT + " WHERE id = ? FOR UPDATE", PgProjectRow.class, id).orElse(null);
            if (row == null || !guard.test(toProject(row))) {
                transaction.commit();
                return false;
            }
            var project = toProject(row);
            mutator.accept(project);
            write(project);
            transaction.commit();
            return true;
        }
    }

    private void write(Project project) {
        database.execute("UPDATE " + TABLE + " SET user_id = ?, status = ?, stats_dirty = ?, analysis_status = ?, "
                        + "last_analyzed_at = ?, last_analysis_at = ?, created_at = ?, updated_at = ?, archived_at = ?, "
                        + "data = ?::jsonb WHERE id = ?",
                project.userId, project.status, project.statsDirty, project.analysisStatus,
                project.lastAnalyzedAt, project.lastAnalysisAt, project.createdAt, project.updatedAt, project.archivedAt,
                JsonUtil.toJson(project), project.id);
    }

    @Override
    public List<Project> list(String userId, Boolean archivedOnly, int offset, int limit) {
        var sql = new StringBuilder(SELECT.length() + 64).append(SELECT);
        var params = new ArrayList<>();
        var conditions = new ArrayList<String>();
        if (userId != null) {
            conditions.add("user_id = ?");
            params.add(userId);
        }
        if (Boolean.TRUE.equals(archivedOnly)) {
            conditions.add("status = ?");
            params.add(ProjectService.STATUS_ARCHIVED);
        } else if (Boolean.FALSE.equals(archivedOnly)) {
            conditions.add("status = ?");
            params.add(ProjectService.STATUS_ACTIVE);
        }
        if (!conditions.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", conditions));
        sql.append(" ORDER BY created_at DESC LIMIT ? OFFSET ?");
        params.add(limit);
        params.add(offset);
        return database.select(sql.toString(), PgProjectRow.class, params.toArray())
                .stream().map(PgProjectStore::toProject).toList();
    }

    @Override
    public long count(String userId, Boolean archivedOnly) {
        var sql = new StringBuilder("SELECT count(*) AS total FROM " + TABLE);
        var params = new ArrayList<>();
        var conditions = new ArrayList<String>();
        if (userId != null) {
            conditions.add("user_id = ?");
            params.add(userId);
        }
        if (Boolean.TRUE.equals(archivedOnly)) {
            conditions.add("status = ?");
            params.add(ProjectService.STATUS_ARCHIVED);
        } else if (Boolean.FALSE.equals(archivedOnly)) {
            conditions.add("status = ?");
            params.add(ProjectService.STATUS_ACTIVE);
        }
        if (!conditions.isEmpty()) sql.append(" WHERE ").append(String.join(" AND ", conditions));
        return database.selectOne(sql.toString(), PgProjectCountRow.class, params.toArray()).map(row -> row.total).orElse(0L);
    }

    @Override
    public List<Project> listForAttribution(ZonedDateTime cutoff, int limit) {
        return database.select(SELECT + " WHERE status = ? AND analysis_status IS DISTINCT FROM ? "
                        + "AND (last_analyzed_at IS NULL OR last_analyzed_at < ?) ORDER BY created_at LIMIT ?",
                PgProjectRow.class, ProjectService.STATUS_ACTIVE, ProjectService.ANALYSIS_RUNNING, cutoff, limit)
                .stream().map(PgProjectStore::toProject).toList();
    }

    @Override
    public List<Project> listForAnalysis(ZonedDateTime cutoff, int limit) {
        return database.select(SELECT + " WHERE status = ? AND analysis_status IS DISTINCT FROM ? "
                        + "AND (last_analysis_at IS NULL OR last_analysis_at < ?) ORDER BY created_at LIMIT ?",
                PgProjectRow.class, ProjectService.STATUS_ACTIVE, ProjectService.ANALYSIS_RUNNING, cutoff, limit)
                .stream().map(PgProjectStore::toProject).toList();
    }

    @Override
    public List<Project> listDirtyStats(int limit) {
        return database.select(SELECT + " WHERE status = ? AND stats_dirty = true ORDER BY created_at LIMIT ?",
                        PgProjectRow.class, ProjectService.STATUS_ACTIVE, limit)
                .stream().map(PgProjectStore::toProject).toList();
    }

}
