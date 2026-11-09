package ai.core.server.project;

import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public class PgProjectAttributionRepository implements ProjectAttributionRepository {
    private static final String TABLE = "app.project_subject_attributions";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;
    private static final ZonedDateTime EPOCH = ZonedDateTime.of(1970, 1, 1, 0, 0, 0, 0, ZoneOffset.UTC);

    @Inject
    Database database;

    private PgDocumentStore<ProjectSubjectAttribution> store() {
        return new PgDocumentStore<>(database, TABLE, ProjectSubjectAttribution.class, attribution -> attribution.id);
    }

    @Override
    public Optional<ProjectSubjectAttribution> get(String id) {
        return store().get(id);
    }

    @Override
    public List<ProjectSubjectAttribution> byTarget(String targetType, String targetId) {
        return database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ?", PgDocumentRow.class,
                "targetType", targetType, "targetId", targetId)
            .stream().map(this::toAttribution).toList();
    }

    @Override
    public Map<String, String> fileSubjects(String projectId) {
        var result = new HashMap<String, String>();
        for (var row : database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ?", PgDocumentRow.class,
            "projectId", projectId, "targetType", ProjectAttributionStore.TARGET_FILE)) {
            var attribution = toAttribution(row);
            result.putIfAbsent(attribution.targetId, attribution.subjectId);
        }
        return result;
    }

    @Override
    public Optional<ProjectSubjectAttribution> fileAttribution(String projectId, String fileId) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ? LIMIT 1",
                PgDocumentRow.class, "projectId", projectId, "targetType", ProjectAttributionStore.TARGET_FILE,
                "targetId", fileId)
            .map(this::toAttribution);
    }

    @Override
    public List<ProjectSubjectAttribution> filedFiles(String projectId, String subjectId,
                                                       ZonedDateTime from, ZonedDateTime to, int offset, int limit) {
        var params = new ArrayList<Object>();
        var sql = SELECT + filedWhere(projectId, subjectId, from, to, params)
            + " ORDER BY (data ->> ?)::timestamptz DESC LIMIT ? OFFSET ?";
        params.add("targetCreatedAt");
        params.add(limit);
        params.add(offset);
        return database.select(sql, PgDocumentRow.class, params.toArray())
            .stream().map(this::toAttribution).toList();
    }

    @Override
    public long filedFileCount(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to) {
        var params = new ArrayList<Object>();
        var sql = "SELECT count(*) AS total FROM " + TABLE + filedWhere(projectId, subjectId, from, to, params);
        return database.selectOne(sql, PgProjectCountRow.class, params.toArray()).map(row -> row.total).orElse(0L);
    }

    @Override
    public Map<String, ProjectAttributionStore.SubjectFileStats> fileStatsBySubject(String projectId) {
        var sql = "SELECT data ->> ? AS subject_id, count(*) AS total, "
            + "(extract(epoch FROM max((data ->> ?)::timestamptz)) * 1000)::bigint AS latest "
            + "FROM " + TABLE + " WHERE data ->> ? = ? AND data ->> ? = ? GROUP BY subject_id";
        var rows = database.select(sql, PgSubjectFileStatsRow.class,
            "subjectId", "targetCreatedAt", "projectId", projectId, "targetType", ProjectAttributionStore.TARGET_FILE);
        var result = new HashMap<String, ProjectAttributionStore.SubjectFileStats>();
        for (var row : rows) {
            if (row.subjectId == null) continue;
            var latest = row.latest == null ? null : ZonedDateTime.ofInstant(Instant.ofEpochMilli(row.latest), ZoneOffset.UTC);
            result.put(row.subjectId, new ProjectAttributionStore.SubjectFileStats(row.total, latest));
        }
        return result;
    }

    @Override
    public boolean exists(String subjectId, String targetType, String targetId) {
        return countBySubjectAndTarget(subjectId, targetType, targetId) > 0;
    }

    @Override
    public boolean insertIfAbsent(ProjectSubjectAttribution attribution) {
        return database.execute("INSERT INTO " + TABLE + " (id, data, updated_at) VALUES (?, ?::jsonb, now()) "
            + "ON CONFLICT DO NOTHING", attribution.id, JsonUtil.toJson(attribution)) > 0;
    }

    @Override
    public void update(String id, Consumer<ProjectSubjectAttribution> mutator) {
        try (var transaction = database.beginTransaction()) {
            var row = database.selectOne(SELECT + " WHERE id = ? FOR UPDATE", PgDocumentRow.class, id).orElse(null);
            if (row == null) {
                transaction.commit();
                return;
            }
            var attribution = toAttribution(row);
            mutator.accept(attribution);
            store().replace(attribution);
            transaction.commit();
        }
    }

    @Override
    public void deleteById(String id) {
        store().delete(id);
    }

    @Override
    public void deleteBySubject(String subjectId) {
        database.execute("DELETE FROM " + TABLE + " WHERE data ->> ? = ?", "subjectId", subjectId);
    }

    @Override
    public void deleteByProjectAndTarget(String projectId, String targetType, String targetId) {
        database.execute("DELETE FROM " + TABLE + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?",
            "projectId", projectId, "targetType", targetType, "targetId", targetId);
    }

    @Override
    public void clearAnalyzed(String projectId, String targetType, String targetId) {
        database.execute("UPDATE " + TABLE + " SET data = data - ?::text, updated_at = now() "
                + "WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?",
            "analyzedAt", "projectId", projectId, "targetType", targetType, "targetId", targetId);
    }

    @Override
    public void deleteByTargetTypeAndId(String targetType, String targetId) {
        database.execute("DELETE FROM " + TABLE + " WHERE data ->> ? = ? AND data ->> ? = ?",
            "targetType", targetType, "targetId", targetId);
    }

    @Override
    public List<ProjectSubjectAttribution> listBySubject(String subjectId) {
        return database.select(SELECT + " WHERE data ->> ? = ?", PgDocumentRow.class, "subjectId", subjectId)
            .stream().map(this::toAttribution).toList();
    }

    @Override
    public long countBySubject(String subjectId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE data ->> ? = ?",
            PgProjectCountRow.class, "subjectId", subjectId).map(row -> row.total).orElse(0L);
    }

    @Override
    public List<ProjectSubjectAttribution> listBySubjectAndType(String subjectId, String targetType) {
        return database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ?", PgDocumentRow.class,
                "subjectId", subjectId, "targetType", targetType)
            .stream().map(this::toAttribution).toList();
    }

    @Override
    public List<ProjectSubjectAttribution> listBySubjectsAndType(Collection<String> subjectIds, String targetType) {
        if (subjectIds.isEmpty()) return List.of();
        var placeholders = String.join(", ", Collections.nCopies(subjectIds.size(), "?"));
        var params = new ArrayList<Object>();
        params.add("targetType");
        params.add(targetType);
        params.add("subjectId");
        params.addAll(subjectIds);
        return database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? IN (" + placeholders + ")",
                PgDocumentRow.class, params.toArray())
            .stream().map(this::toAttribution).toList();
    }

    @Override
    public List<ProjectSubjectAttribution> listByProjectAndType(String projectId, String targetType) {
        return database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ?", PgDocumentRow.class,
                "projectId", projectId, "targetType", targetType)
            .stream().map(this::toAttribution).toList();
    }

    @Override
    public List<ProjectSubjectAttribution> listUnanalyzed(String subjectId, int limit) {
        return database.select(SELECT + " WHERE data ->> ? = ? AND (data ->> ?) IS NULL LIMIT ?", PgDocumentRow.class,
                "subjectId", subjectId, "analyzedAt", limit)
            .stream().map(this::toAttribution).toList();
    }

    @Override
    public long countUnanalyzed(String subjectId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE data ->> ? = ? AND (data ->> ?) IS NULL",
            PgProjectCountRow.class, "subjectId", subjectId, "analyzedAt").map(row -> row.total).orElse(0L);
    }

    @Override
    public List<ProjectSubjectAttribution> listByProjectAndTargets(String projectId, String targetType, Collection<String> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        var placeholders = String.join(", ", Collections.nCopies(targetIds.size(), "?"));
        var params = new ArrayList<Object>();
        params.add("projectId");
        params.add(projectId);
        params.add("targetType");
        params.add(targetType);
        params.add("targetId");
        params.addAll(targetIds);
        return database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? IN (" + placeholders + ")",
                PgDocumentRow.class, params.toArray())
            .stream().map(this::toAttribution).toList();
    }

    @Override
    public long countByProjectAndTarget(String projectId, String targetType, String targetId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE
                + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?",
                PgProjectCountRow.class, "projectId", projectId, "targetType", targetType, "targetId", targetId)
            .map(row -> row.total).orElse(0L);
    }

    private String filedWhere(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to, List<Object> params) {
        var conditions = new ArrayList<String>();
        conditions.add("data ->> ? = ?");
        params.add("projectId");
        params.add(projectId);
        if (subjectId != null && !subjectId.isBlank()) {
            conditions.add("data ->> ? = ?");
            params.add("subjectId");
            params.add(subjectId);
        }
        conditions.add("data ->> ? = ?");
        params.add("targetType");
        params.add(ProjectAttributionStore.TARGET_FILE);
        conditions.add("(data ->> ?)::timestamptz >= ?");
        params.add("targetCreatedAt");
        params.add(from != null ? from : EPOCH);
        if (to != null) {
            conditions.add("(data ->> ?)::timestamptz <= ?");
            params.add("targetCreatedAt");
            params.add(to);
        }
        return " WHERE " + String.join(" AND ", conditions);
    }

    private long countBySubjectAndTarget(String subjectId, String targetType, String targetId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE
                + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?",
                PgProjectCountRow.class, "subjectId", subjectId, "targetType", targetType, "targetId", targetId)
            .map(row -> row.total).orElse(0L);
    }

    private ProjectSubjectAttribution toAttribution(PgDocumentRow row) {
        return JsonUtil.fromJson(ProjectSubjectAttribution.class, row.data);
    }
}
