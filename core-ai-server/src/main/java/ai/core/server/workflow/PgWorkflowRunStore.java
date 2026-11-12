package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.RunStatus;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.domain.WorkflowVisibility;
import ai.core.server.util.JsonSanitizer;
import ai.core.utils.JsonUtil;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class PgWorkflowRunStore implements WorkflowRunStore {
    private static final String TABLE = "app.workflow_runs";
    private static final String SELECT = "SELECT id, workflow_id, version_id, user_id, mode, session_id, triggered_by, "
        + "preview, parent_run_id, status, visibility, claimed_by, lease_until, started_at, completed_at, created_at, "
        + "output, error, artifacts::text AS artifacts, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, workflow_id, version_id, user_id, mode, "
        + "session_id, triggered_by, preview, parent_run_id, status, visibility, claimed_by, lease_until, started_at, "
        + "completed_at, created_at, output, error, artifacts, data) ";

    private static WorkflowRun toRun(PgWorkflowRunRow row) {
        var run = JsonUtil.fromJson(WorkflowRun.class, row.data);
        run.id = row.id;
        run.workflowId = row.workflowId;
        run.versionId = row.versionId;
        run.userId = row.userId;
        run.mode = row.mode != null ? ai.core.server.domain.WorkflowMode.valueOf(row.mode) : null;
        run.sessionId = row.sessionId;
        run.triggeredBy = row.triggeredBy != null ? ai.core.server.domain.TriggerType.valueOf(row.triggeredBy) : null;
        run.preview = row.preview;
        run.parentRunId = row.parentRunId;
        run.status = row.status != null ? RunStatus.valueOf(row.status) : null;
        run.visibility = row.visibility != null ? WorkflowVisibility.valueOf(row.visibility) : null;
        run.claimedBy = row.claimedBy;
        run.leaseUntil = row.leaseUntil;
        run.startedAt = row.startedAt;
        run.completedAt = row.completedAt;
        run.createdAt = row.createdAt;
        run.output = row.output;
        run.error = row.error;
        if (row.artifacts != null) {
            run.artifacts = Arrays.asList(JsonUtil.fromJson(ArtifactRef[].class, row.artifacts));
        }
        return run;
    }

    @Inject
    public Database database;

    @Override
    public Optional<WorkflowRun> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgWorkflowRunRow.class, id)
            .map(PgWorkflowRunStore::toRun);
    }

    @Override
    public void insert(WorkflowRun run) {
        database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb)",
            params(run));
    }

    @Override
    public List<WorkflowRun> findClaimable(ZonedDateTime now) {
        return database.select(SELECT + " WHERE status IN (?, ?) AND lease_until <= ? ORDER BY created_at, id",
                PgWorkflowRunRow.class, RunStatus.PENDING.name(), RunStatus.RUNNING.name(), now)
            .stream().map(PgWorkflowRunStore::toRun).toList();
    }

    @Override
    public List<WorkflowRun> findStalePaused(ZonedDateTime now) {
        return database.select(SELECT + " WHERE status = ? AND lease_until <= ? ORDER BY created_at, id",
                PgWorkflowRunRow.class, RunStatus.PAUSED.name(), now)
            .stream().map(PgWorkflowRunStore::toRun).toList();
    }

    @Override
    public List<WorkflowRun> findChildren(String parentRunId) {
        return database.select(SELECT + " WHERE parent_run_id = ?", PgWorkflowRunRow.class, parentRunId)
            .stream().map(PgWorkflowRunStore::toRun).toList();
    }

    @Override
    public List<WorkflowRun> findByIds(List<String> ids) {
        if (ids.isEmpty()) return List.of();
        var placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
        return database.select(SELECT + " WHERE id IN (" + placeholders + ")", PgWorkflowRunRow.class, ids.toArray())
            .stream().map(PgWorkflowRunStore::toRun).toList();
    }

    @Override
    public List<WorkflowRun> listByWorkflow(String workflowId, String userId, boolean admin) {
        var sql = new StringBuilder(512).append(SELECT).append(" WHERE workflow_id = ?");
        var params = new ArrayList<>();
        params.add(workflowId);
        if (!admin) {
            sql.append(" AND (visibility = ? OR user_id = ?)");
            params.add("PUBLIC");
            params.add(userId);
        }
        sql.append(" ORDER BY created_at DESC NULLS LAST, id DESC");
        return database.select(sql.toString(), PgWorkflowRunRow.class, params.toArray())
            .stream().map(PgWorkflowRunStore::toRun).toList();
    }

    @Override
    public List<WorkflowRun> find(WorkflowRunQuery request) {
        var where = filter(request);
        var sql = new StringBuilder(512).append(SELECT).append(where.sql)
            .append(request.ascending ? " ORDER BY started_at ASC NULLS FIRST, id" : " ORDER BY started_at DESC NULLS LAST, id DESC")
            .append(" LIMIT ? OFFSET ?");
        var params = new ArrayList<>(where.params);
        params.add(Math.min(Math.max(request.limit, 1), 1000));
        params.add(Math.max(0, request.offset));
        return database.select(sql.toString(), PgWorkflowRunRow.class, params.toArray())
            .stream().map(PgWorkflowRunStore::toRun).toList();
    }

    @Override
    public long count(WorkflowRunQuery request) {
        var where = filter(request);
        return database.selectOne("SELECT count(*) AS total FROM " + TABLE + where.sql,
                PgWorkflowRunCountRow.class, where.params.toArray()).map(row -> row.total).orElse(0L);
    }

    private Where filter(WorkflowRunQuery request) {
        var conditions = new ArrayList<String>();
        var params = new ArrayList<>();
        if (request.workflowIds != null) {
            if (request.workflowIds.isEmpty()) return new Where(" WHERE 1 = 0", params);
            addIn(conditions, params, "workflow_id", request.workflowIds);
        }
        if (request.ids != null) {
            if (request.ids.isEmpty()) return new Where(" WHERE 1 = 0", params);
            addIn(conditions, params, "id", request.ids);
        }
        if (request.excludeIds != null && !request.excludeIds.isEmpty()) {
            var placeholders = String.join(",", java.util.Collections.nCopies(request.excludeIds.size(), "?"));
            conditions.add("id NOT IN (" + placeholders + ")");
            params.addAll(request.excludeIds);
        }
        if (request.from != null) {
            conditions.add("started_at " + (request.exclusiveFrom ? ">" : ">=") + " ?");
            params.add(request.from);
        }
        if (!request.includePreview) conditions.add("(preview IS NULL OR preview = false)");
        return new Where(conditions.isEmpty() ? "" : " WHERE " + String.join(" AND ", conditions), params);
    }

    private void addIn(List<String> conditions, List<Object> params, String column, List<String> values) {
        var placeholders = String.join(",", java.util.Collections.nCopies(values.size(), "?"));
        conditions.add(column + " IN (" + placeholders + ")");
        params.addAll(values);
    }

    @Override
    public boolean claim(String id, String workerId, ZonedDateTime now, ZonedDateTime leaseUntil) {
        return database.execute("UPDATE " + TABLE + " SET claimed_by = ?, status = ?, lease_until = ? "
                + "WHERE id = ? AND status IN (?, ?) AND lease_until <= ?",
            workerId, RunStatus.RUNNING.name(), leaseUntil, id, RunStatus.PENDING.name(), RunStatus.RUNNING.name(), now) > 0;
    }

    @Override
    public void markStartedIfNull(String id, ZonedDateTime startedAt) {
        database.execute("UPDATE " + TABLE + " SET started_at = ? WHERE id = ? AND started_at IS NULL", startedAt, id);
    }

    @Override
    public boolean renewLease(String id, String workerId, ZonedDateTime leaseUntil) {
        return database.execute("UPDATE " + TABLE + " SET lease_until = ? WHERE id = ? AND claimed_by = ?",
            leaseUntil, id, workerId) > 0;
    }

    @Override
    public boolean pause(String id, String workerId) {
        return database.execute("UPDATE " + TABLE + " SET status = ? WHERE id = ? AND claimed_by = ? AND status = ?",
            RunStatus.PAUSED.name(), id, workerId, RunStatus.RUNNING.name()) > 0;
    }

    @Override
    public boolean terminate(String id, String workerId, RunTermination termination) {
        var status = termination.status();
        var error = termination.error();
        var output = termination.output();
        var artifacts = termination.artifacts();
        var completedAt = termination.completedAt();
        var sql = new StringBuilder(512).append("UPDATE ").append(TABLE)
            .append(" SET status = ?, completed_at = ?");
        var params = new ArrayList<>();
        params.add(status.name());
        params.add(completedAt);
        if (error != null) {
            sql.append(", error = ?");
            params.add(JsonSanitizer.text(error));
        }
        if (output != null) {
            sql.append(", output = ?");
            params.add(JsonSanitizer.text(output));
        }
        if (artifacts != null && !artifacts.isEmpty()) {
            sql.append(", artifacts = ?::jsonb");
            params.add(JsonSanitizer.json(JsonUtil.toJson(artifacts)));
        }
        sql.append(" WHERE id = ? AND claimed_by = ? AND completed_at IS NULL");
        params.add(id);
        params.add(workerId);
        return database.execute(sql.toString(), params.toArray()) > 0;
    }

    @Override
    public boolean wakePaused(String id, ZonedDateTime now) {
        return database.execute("UPDATE " + TABLE + " SET status = ?, lease_until = ? WHERE id = ? AND status = ?",
            RunStatus.PENDING.name(), now, id, RunStatus.PAUSED.name()) > 0;
    }

    @Override
    public boolean cancelIfUnfinished(String id) {
        return database.execute("UPDATE " + TABLE + " SET status = ? WHERE id = ? AND completed_at IS NULL",
            RunStatus.CANCELLED.name(), id) > 0;
    }

    private Object[] params(WorkflowRun run) {
        return new Object[]{
            run.id,
            run.workflowId,
            run.versionId,
            run.userId,
            run.mode != null ? run.mode.name() : null,
            run.sessionId,
            run.triggeredBy != null ? run.triggeredBy.name() : null,
            run.preview,
            run.parentRunId,
            run.status != null ? run.status.name() : null,
            run.visibility != null ? run.visibility.name() : null,
            run.claimedBy,
            run.leaseUntil,
            run.startedAt,
            run.completedAt,
            run.createdAt,
            JsonSanitizer.text(run.output),
            JsonSanitizer.text(run.error),
            run.artifacts == null ? null : JsonSanitizer.json(JsonUtil.toJson(run.artifacts)),
            JsonSanitizer.json(JsonUtil.toJson(run))
        };
    }

    private record Where(String sql, List<Object> params) {
    }
}



