package ai.core.server.workflow;

import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgWorkflowVersionStore implements WorkflowVersionStore {
    private static final String TABLE = "app.workflow_published_versions";
    private static final String SELECT = "SELECT id, workflow_id, version, preview, status, sha256, published_by, "
        + "published_at, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, workflow_id, version, preview, status, sha256, "
        + "published_by, published_at, data) ";

    private static WorkflowPublishedVersion toVersion(PgWorkflowVersionRow row) {
        var version = JsonUtil.fromJson(WorkflowPublishedVersion.class, row.data);
        version.id = row.id;
        version.workflowId = row.workflowId;
        version.version = row.version;
        version.preview = row.preview;
        version.status = row.status != null ? ai.core.server.domain.WorkflowVersionStatus.valueOf(row.status) : null;
        version.sha256 = row.sha256;
        version.publishedBy = row.publishedBy;
        version.publishedAt = row.publishedAt;
        return version;
    }

    @Inject
    public Database database;

    @Override
    public Optional<WorkflowPublishedVersion> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgWorkflowVersionRow.class, id)
            .map(PgWorkflowVersionStore::toVersion);
    }

    @Override
    public void insert(WorkflowPublishedVersion version) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)", params(version));
    }

    @Override
    public List<WorkflowPublishedVersion> listNonPreview(String workflowId) {
        return database.select(SELECT + " WHERE workflow_id = ? AND (preview IS NULL OR preview = false) "
                + "ORDER BY version DESC NULLS LAST, id", PgWorkflowVersionRow.class, workflowId)
            .stream().map(PgWorkflowVersionStore::toVersion).toList();
    }

    @Override
    public List<WorkflowPublishedVersion> listAll(String workflowId) {
        return database.select(SELECT + " WHERE workflow_id = ?", PgWorkflowVersionRow.class, workflowId)
            .stream().map(PgWorkflowVersionStore::toVersion).toList();
    }

    @Override
    public int deletePreviewOlderThan(ZonedDateTime cutoff) {
        return database.execute("DELETE FROM " + TABLE + " WHERE preview = true AND published_at < ?", cutoff);
    }

    private Object[] params(WorkflowPublishedVersion version) {
        return new Object[]{
            version.id,
            version.workflowId,
            version.version,
            version.preview,
            version.status != null ? version.status.name() : null,
            version.sha256,
            version.publishedBy,
            version.publishedAt,
            JsonSanitizer.json(JsonUtil.toJson(version))
        };
    }
}
