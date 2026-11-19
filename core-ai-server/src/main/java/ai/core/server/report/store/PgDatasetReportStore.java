package ai.core.server.report.store;

import ai.core.server.report.domain.ReportDefinition;
import ai.core.server.report.domain.ReportDependency;
import ai.core.server.report.domain.ReportSpec;
import ai.core.server.dataset.store.PgCountRow;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.util.List;
import java.util.Optional;

/**
 * PostgreSQL storage for report definitions.
 *
 * @author core-ai
 */
public class PgDatasetReportStore {
    private static final String TABLE = "app.dataset_reports";
    private static final String COLUMNS = "id, user_id, title, description, revision, spec_version, engine_version, "
            + "spec_json::text AS spec_json, dataset_id, dependency_json::text AS dependency_json, "
            + "origin_session_id, origin_agent_id, created_at, updated_at";

    @Inject
    Database database;

    public void insert(ReportDefinition report) {
        database.execute("INSERT INTO " + TABLE + " (id, user_id, title, description, revision, spec_version, engine_version, "
                        + "spec_json, dataset_id, dependency_json, origin_session_id, origin_agent_id, created_at, updated_at) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?::jsonb, ?, ?, ?, ?)",
                report.id, report.userId, report.title, report.description, report.revision, report.specVersion,
                report.engineVersion, report.specJson, report.datasetId, report.dependencyJson,
                report.originSessionId, report.originAgentId, report.createdAt, report.updatedAt);
    }

    public Optional<ReportDefinition> getOwned(String id, String userId) {
        return database.selectOne("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND user_id = ?",
                PgDatasetReportRow.class, id, userId).map(this::toDomain);
    }

    public Optional<ReportDefinition> lockOwned(String id, String userId) {
        return database.selectOne("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE id = ? AND user_id = ? FOR UPDATE",
                PgDatasetReportRow.class, id, userId).map(this::toDomain);
    }

    public List<ReportDefinition> listOwned(String userId, int offset, int limit) {
        return database.select("SELECT " + COLUMNS + " FROM " + TABLE
                        + " WHERE user_id = ? ORDER BY updated_at DESC, id LIMIT ? OFFSET ?",
                PgDatasetReportRow.class, userId, limit, offset).stream().map(this::toDomain).toList();
    }

    public long countOwned(String userId) {
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + " WHERE user_id = ?",
                PgCountRow.class, userId).map(row -> row.total).orElse(0L);
    }

    public boolean update(ReportDefinition report, long expectedRevision) {
        return database.execute("UPDATE " + TABLE + " SET title = ?, description = ?, revision = revision + 1, "
                        + "spec_version = ?, engine_version = ?, spec_json = ?::jsonb, dataset_id = ?, dependency_json = ?::jsonb, "
                        + "origin_session_id = ?, origin_agent_id = ?, updated_at = ? "
                        + "WHERE id = ? AND user_id = ? AND revision = ?",
                report.title, report.description, report.specVersion, report.engineVersion, report.specJson,
                report.datasetId, report.dependencyJson, report.originSessionId, report.originAgentId, report.updatedAt,
                report.id, report.userId, expectedRevision) > 0;
    }

    public boolean delete(String id, String userId, long expectedRevision) {
        return database.execute("DELETE FROM " + TABLE + " WHERE id = ? AND user_id = ? AND revision = ?",
                id, userId, expectedRevision) > 0;
    }

    private ReportDefinition toDomain(PgDatasetReportRow row) {
        var report = new ReportDefinition();
        report.id = row.id;
        report.userId = row.userId;
        report.title = row.title;
        report.description = row.description;
        report.revision = row.revision != null ? row.revision : 0;
        report.specVersion = row.specVersion != null ? row.specVersion : 0;
        report.engineVersion = row.engineVersion != null ? row.engineVersion : 0;
        report.specJson = row.specJson;
        report.spec = row.specJson != null ? JsonUtil.fromJson(ReportSpec.class, row.specJson) : null;
        report.datasetId = row.datasetId;
        report.dependencyJson = row.dependencyJson;
        report.dependency = row.dependencyJson != null ? JsonUtil.fromJson(ReportDependency.class, row.dependencyJson) : null;
        report.originSessionId = row.originSessionId;
        report.originAgentId = row.originAgentId;
        report.createdAt = row.createdAt;
        report.updatedAt = row.updatedAt;
        return report;
    }
}
