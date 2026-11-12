package ai.core.server.workflow;

import ai.core.server.domain.ArtifactRef;
import ai.core.server.domain.NodeRunStatus;
import ai.core.server.domain.WorkflowNodeRun;
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
public class PgWorkflowNodeRunStore implements WorkflowNodeRunStore {
    private static final String TABLE = "app.workflow_node_runs";
    private static final String SELECT = "SELECT id, run_id, workflow_id, node_id, node_type, scope_path_key, status, "
        + "input_json, output, child_run_id, preview, error, started_at, completed_at, created_at, "
        + "artifacts::text AS artifacts, chosen_edge_ids::text AS chosen_edge_ids, data::text AS data FROM " + TABLE;
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, run_id, workflow_id, node_id, node_type, "
        + "scope_path_key, status, input_json, output, child_run_id, preview, error, started_at, completed_at, created_at, "
        + "artifacts, chosen_edge_ids, data) ";

    private static WorkflowNodeRun toNodeRun(PgWorkflowNodeRunRow row) {
        var nodeRun = JsonUtil.fromJson(WorkflowNodeRun.class, row.data);
        nodeRun.id = row.id;
        nodeRun.runId = row.runId;
        nodeRun.workflowId = row.workflowId;
        nodeRun.nodeId = row.nodeId;
        nodeRun.nodeType = row.nodeType;
        nodeRun.scopePathKey = row.scopePathKey;
        nodeRun.status = row.status != null ? NodeRunStatus.valueOf(row.status) : null;
        nodeRun.inputJson = row.inputJson;
        nodeRun.output = row.output;
        nodeRun.childRunId = row.childRunId;
        nodeRun.preview = row.preview;
        nodeRun.error = row.error;
        nodeRun.startedAt = row.startedAt;
        nodeRun.completedAt = row.completedAt;
        nodeRun.createdAt = row.createdAt;
        if (row.artifacts != null) nodeRun.artifacts = Arrays.asList(JsonUtil.fromJson(ArtifactRef[].class, row.artifacts));
        if (row.chosenEdgeIds != null) nodeRun.chosenEdgeIds = Arrays.asList(JsonUtil.fromJson(String[].class, row.chosenEdgeIds));
        return nodeRun;
    }

    @Inject
    public Database database;

    @Override
    public Optional<WorkflowNodeRun> get(String id) {
        return database.selectOne(SELECT + " WHERE id = ?", PgWorkflowNodeRunRow.class, id)
            .map(PgWorkflowNodeRunStore::toNodeRun);
    }

    @Override
    public boolean insertIfAbsent(WorkflowNodeRun nodeRun) {
        return database.execute(INSERT + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?::jsonb) "
            + "ON CONFLICT DO NOTHING", params(nodeRun)) > 0;
    }

    @Override
    public void replace(WorkflowNodeRun nodeRun) {
        database.execute("UPDATE " + TABLE + " SET run_id = ?, workflow_id = ?, node_id = ?, node_type = ?, "
                + "scope_path_key = ?, status = ?, input_json = ?, output = ?, child_run_id = ?, preview = ?, error = ?, "
                + "started_at = ?, completed_at = ?, created_at = ?, artifacts = ?::jsonb, chosen_edge_ids = ?::jsonb, "
                + "data = ?::jsonb WHERE id = ?",
            nodeRun.runId, nodeRun.workflowId, nodeRun.nodeId, nodeRun.nodeType, nodeRun.scopePathKey,
            nodeRun.status != null ? nodeRun.status.name() : null, nodeRun.inputJson, JsonSanitizer.text(nodeRun.output),
            nodeRun.childRunId, nodeRun.preview, JsonSanitizer.text(nodeRun.error), nodeRun.startedAt,
            nodeRun.completedAt, nodeRun.createdAt, json(nodeRun.artifacts), json(nodeRun.chosenEdgeIds),
            JsonSanitizer.json(JsonUtil.toJson(nodeRun)), nodeRun.id);
    }

    @Override
    public List<WorkflowNodeRun> listByRun(String runId) {
        return database.select(SELECT + " WHERE run_id = ? ORDER BY created_at, id", PgWorkflowNodeRunRow.class, runId)
            .stream().map(PgWorkflowNodeRunStore::toNodeRun).toList();
    }

    @Override
    public List<WorkflowNodeRun> findWaitingByRun(String runId) {
        return database.select(SELECT + " WHERE run_id = ? AND status = ?", PgWorkflowNodeRunRow.class,
                runId, NodeRunStatus.WAITING.name())
            .stream().map(PgWorkflowNodeRunStore::toNodeRun).toList();
    }

    @Override
    public List<WorkflowNodeRun> findFailedRetryableByRun(String runId) {
        return database.select(SELECT + " WHERE run_id = ? AND status = ?", PgWorkflowNodeRunRow.class,
                runId, NodeRunStatus.FAILED_RETRYABLE.name())
            .stream().map(PgWorkflowNodeRunStore::toNodeRun).toList();
    }

    @Override
    public List<WorkflowNodeRun> findByRunTypeAndStatus(String runId, String nodeType, NodeRunStatus status) {
        return database.select(SELECT + " WHERE run_id = ? AND node_type = ? AND status = ?", PgWorkflowNodeRunRow.class,
                runId, nodeType, status.name())
            .stream().map(PgWorkflowNodeRunStore::toNodeRun).toList();
    }

    @Override
    public int resetRunningByRun(String runId, ZonedDateTime completedAt) {
        return database.execute("UPDATE " + TABLE + " SET status = ?, error = ?, completed_at = ? "
                + "WHERE run_id = ? AND status = ?",
            NodeRunStatus.FAILED_RETRYABLE.name(), "node interrupted: the previous run owner lost its lease or crashed",
            completedAt, runId, NodeRunStatus.RUNNING.name());
    }

    @Override
    public boolean settleWaiting(String runId, String nodeId, NodeRunStatus status, String output,
                                 List<String> chosenEdgeIds, ZonedDateTime completedAt) {
        var sql = new StringBuilder(512).append("UPDATE ").append(TABLE)
            .append(" SET status = ?, completed_at = ?");
        var params = new ArrayList<>();
        params.add(status.name());
        params.add(completedAt);
        if (output != null) {
            sql.append(", output = ?");
            params.add(JsonSanitizer.text(output));
        }
        if (chosenEdgeIds != null && !chosenEdgeIds.isEmpty()) {
            sql.append(", chosen_edge_ids = ?::jsonb");
            params.add(json(chosenEdgeIds));
        }
        sql.append(" WHERE run_id = ? AND node_id = ? AND status = ?");
        params.add(runId);
        params.add(nodeId);
        params.add(NodeRunStatus.WAITING.name());
        return database.execute(sql.toString(), params.toArray()) > 0;
    }

    @Override
    public boolean settleWaitingChild(String runId, String nodeId, ChildSettlement settlement) {
        var childRunId = settlement.childRunId();
        var sql = new StringBuilder(512).append("UPDATE ").append(TABLE)
            .append(" SET status = ?, completed_at = ?");
        var params = new ArrayList<>();
        params.add(settlement.status().name());
        params.add(settlement.completedAt());
        if (settlement.output() != null) {
            sql.append(", output = ?");
            params.add(JsonSanitizer.text(settlement.output()));
        }
        if (settlement.artifacts() != null && !settlement.artifacts().isEmpty()) {
            sql.append(", artifacts = ?::jsonb");
            params.add(json(settlement.artifacts()));
        }
        if (settlement.error() != null) {
            sql.append(", error = ?");
            params.add(JsonSanitizer.text(settlement.error()));
        }
        sql.append(" WHERE run_id = ? AND node_id = ? AND child_run_id = ? AND status = ?");
        params.add(runId);
        params.add(nodeId);
        params.add(childRunId);
        params.add(NodeRunStatus.WAITING.name());
        return database.execute(sql.toString(), params.toArray()) > 0;
    }

    private String json(List<?> values) {
        return values == null ? null : JsonSanitizer.json(JsonUtil.toJson(values));
    }

    private Object[] params(WorkflowNodeRun nodeRun) {
        return new Object[]{
            nodeRun.id,
            nodeRun.runId,
            nodeRun.workflowId,
            nodeRun.nodeId,
            nodeRun.nodeType,
            nodeRun.scopePathKey,
            nodeRun.status != null ? nodeRun.status.name() : null,
            JsonSanitizer.text(nodeRun.inputJson),
            JsonSanitizer.text(nodeRun.output),
            nodeRun.childRunId,
            nodeRun.preview,
            JsonSanitizer.text(nodeRun.error),
            nodeRun.startedAt,
            nodeRun.completedAt,
            nodeRun.createdAt,
            json(nodeRun.artifacts),
            json(nodeRun.chosenEdgeIds),
            JsonSanitizer.json(JsonUtil.toJson(nodeRun))
        };
    }
}

