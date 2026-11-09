package ai.core.server.project;

import ai.core.server.domain.ProjectTargetScan;
import ai.core.server.pg.PgDocumentRow;
import ai.core.server.pg.PgDocumentStore;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgProjectTargetScanRepository implements ProjectTargetScanRepository {
    private static final String TABLE = "app.project_target_scans";
    private static final String SELECT = "SELECT data::text AS data FROM " + TABLE;

    @Inject
    Database database;

    private PgDocumentStore<ProjectTargetScan> store() {
        return new PgDocumentStore<>(database, TABLE, ProjectTargetScan.class, scan -> scan.id);
    }

    @Override
    public List<ProjectTargetScan> listByProjectAndTargets(String projectId, String targetType, Collection<String> targetIds) {
        if (targetIds.isEmpty()) return List.of();
        var placeholders = String.join(", ", Collections.nCopies(targetIds.size(), "?"));
        var params = new ArrayList<Object>();
        params.add("projectId");
        params.add(projectId);
        params.add("targetType");
        params.add(targetType);
        params.addAll(targetIds);
        return database.select(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? IN (" + placeholders + ")",
                PgDocumentRow.class, params.toArray())
            .stream().map(this::toScan).toList();
    }

    @Override
    public List<ProjectTargetScan> listByProject(String projectId) {
        return database.select(SELECT + " WHERE data ->> ? = ?", PgDocumentRow.class, "projectId", projectId)
            .stream().map(this::toScan).toList();
    }

    @Override
    public Optional<ProjectTargetScan> find(String projectId, String targetType, String targetId) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?",
                PgDocumentRow.class, "projectId", projectId, "targetType", targetType, "targetId", targetId)
            .map(this::toScan);
    }

    @Override
    public void upsert(ProjectTargetScan scan) {
        try (var transaction = database.beginTransaction()) {
            var existing = find(scan.projectId, scan.targetType, scan.targetId).orElse(null);
            if (existing == null) {
                store().insert(scan);
            } else {
                existing.materialAt = scan.materialAt;
                existing.scannedAt = scan.scannedAt;
                store().replace(existing);
            }
            transaction.commit();
        }
    }

    @Override
    public void deleteById(String id) {
        store().delete(id);
    }

    @Override
    public void deleteByProjectAndTarget(String projectId, String targetType, String targetId) {
        database.execute("DELETE FROM " + TABLE + " WHERE data ->> ? = ? AND data ->> ? = ? AND data ->> ? = ?",
            "projectId", projectId, "targetType", targetType, "targetId", targetId);
    }

    @Override
    public Optional<ZonedDateTime> oldestMaterialAt(String projectId) {
        return database.selectOne(SELECT + " WHERE data ->> ? = ? ORDER BY (data ->> ?)::timestamptz ASC LIMIT 1",
                PgDocumentRow.class, "projectId", projectId, "materialAt")
            .map(row -> toScan(row).materialAt);
    }

    private ProjectTargetScan toScan(PgDocumentRow row) {
        return JsonUtil.fromJson(ProjectTargetScan.class, row.data);
    }
}
