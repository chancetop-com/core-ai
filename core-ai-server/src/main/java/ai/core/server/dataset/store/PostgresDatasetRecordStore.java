package ai.core.server.dataset.store;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.Dataset;
import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import core.framework.db.Database;
import core.framework.db.Transaction;
import core.framework.inject.Inject;

import java.time.OffsetDateTime;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PostgreSQL storage: {@code data} stays a String on the Java side and is written/read through an
 * explicit jsonb cast, so the entity model does not need a Map field and the database can filter, sort
 * and aggregate without the 10k in-memory scan.
 *
 * @author stephen
 */
public class PostgresDatasetRecordStore implements DatasetRecordStore {
    private static final String TABLE = "dataset.dataset_records";

    private static final String COLUMNS = "id, dataset_id, agent_id, run_id, session_id, user_id, data::text AS data, "
            + "run_started_at, created_at, created_by, updated_at, updated_by";

    private static final String INSERT = "INSERT INTO " + TABLE + " (id, dataset_id, agent_id, run_id, session_id, user_id, data, "
            + "run_started_at, created_at, created_by, updated_at, updated_by) VALUES (?, ?, ?, ?, ?, ?, ?::jsonb, ?, ?, ?, ?, ?)";

    private static final String UPSERT_BY_SESSION = INSERT
            + " ON CONFLICT (dataset_id, session_id) WHERE session_id IS NOT NULL"
            + " DO UPDATE SET data = EXCLUDED.data, run_started_at = EXCLUDED.run_started_at,"
            + " updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by";

    private static final String PATCH_BY_SESSION = INSERT
            + " ON CONFLICT (dataset_id, session_id) WHERE session_id IS NOT NULL"
            + " DO UPDATE SET data = dataset.jsonb_merge_patch(" + TABLE + ".data, EXCLUDED.data),"
            + " run_started_at = EXCLUDED.run_started_at, updated_at = EXCLUDED.updated_at, updated_by = EXCLUDED.updated_by";

    private static List<Object> normalize(List<Object> row) {
        if (row.isEmpty() || row.getFirst() == null) return row;
        var normalized = new ArrayList<>(row);
        normalized.set(0, OffsetDateTime.parse(String.valueOf(row.getFirst())).toInstant().toString());
        return normalized;
    }

    private static final TypeReference<List<Object>> ROW_TYPE = new TypeReference<>() { };

    @Inject
    Database database;

    @Inject
    DatasetMetaStore datasetMetaStore;

    @Inject
    DatasetViewManager viewManager;

    @Override
    public void insert(DatasetRecord record) {
        database.execute(INSERT, params(record));
    }

    private Object[] params(DatasetRecord record) {
        var params = new ArrayList<>();
        params.add(record.id);
        params.add(record.datasetId);
        params.add(record.agentId);
        params.add(record.runId);
        params.add(record.sessionId);
        params.add(record.userId);
        params.add(record.data != null ? record.data : "{}");
        params.add(record.runStartedAt);
        params.add(record.createdAt);
        params.add(record.createdBy);
        params.add(record.updatedAt);
        params.add(record.updatedBy);
        return params.toArray();
    }

    @Override
    public DatasetRecordService.QueryResult query(DatasetRecordService.QueryRequest request) {
        var view = viewSource(request.datasetId(), request.filter());
        var source = view != null ? DatasetViewManager.qualifiedView(view.viewName()) : TABLE;
        var filter = view != null ? view.clause() : DatasetFilterSql.build(request.filter());
        var where = new StringBuilder(128).append("dataset_id = ?");
        var params = new ArrayList<>();
        params.add(request.datasetId());
        if (request.from() != null) {
            where.append(" AND run_started_at >= ?");
            params.add(request.from());
        }
        if (request.to() != null) {
            where.append(" AND run_started_at <= ?");
            params.add(request.to());
        }
        if (request.agentId() != null) {
            where.append(" AND agent_id = ?");
            params.add(request.agentId());
        }
        if (filter.sql() != null) where.append(" AND ").append(filter.sql());
        params.addAll(filter.params());

        int limit = request.limit() != null ? Math.min(Math.max(request.limit(), 1), DatasetRecordService.MAX_PAGE_SIZE) : 100;
        int offset = request.offset() != null ? Math.max(request.offset(), 0) : 0;

        var pageParams = new ArrayList<>(params);
        pageParams.add(limit);
        pageParams.add(offset);
        var rows = database.select("SELECT " + COLUMNS + " FROM " + source
                + " WHERE " + where + " ORDER BY run_started_at DESC, id LIMIT ? OFFSET ?", PgDatasetRecordRow.class, pageParams.toArray());

        var recordParams = new ArrayList<>();
        recordParams.add(request.datasetId());
        if (request.from() != null) recordParams.add(request.from());
        if (request.to() != null) recordParams.add(request.to());
        if (request.agentId() != null) recordParams.add(request.agentId());
        recordParams.addAll(filter.params());
        var total = database.selectOne("SELECT count(*) AS total FROM " + source + " WHERE " + where, PgCountRow.class, recordParams.toArray())
                .map(row -> row.total)
                .orElse(0L);

        var records = rows.stream().map(this::toRecord).toList();
        return new DatasetRecordService.QueryResult(
                DatasetRecordService.trimFields(records, request.fields()), total, false);
    }

    /**
     * Schema fields are queried through the generated view so the planner can use an index built from the
     * same expression as the view column. Any filter shape the view cannot express returns null and the
     * caller keeps the jsonb path on the base table.
     */
    private ViewSource viewSource(String datasetId, Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) return null;
        var dataset = datasetMetaStore.get(datasetId);
        if (dataset == null) return null;
        var view = database.selectOne("SELECT dataset_id, view_name, column_map, schema_hash, updated_at FROM app.dataset_views WHERE dataset_id = ?",
                PgDatasetViewRow.class, datasetId).orElse(null);
        if (view == null) return null;
        var columns = new LinkedHashMap<String, DatasetViewManager.Column>();
        for (var column : viewManager.columns(dataset)) {
            columns.put(column.sourceName(), column);
        }
        var clause = DatasetViewFilterSql.build(filter, columns);
        return clause != null ? new ViewSource(view.viewName, clause) : null;
    }

    private DatasetRecord toRecord(PgDatasetRecordRow row) {
        var record = new DatasetRecord();
        record.id = row.id;
        record.datasetId = row.datasetId;
        record.agentId = row.agentId;
        record.runId = row.runId;
        record.sessionId = row.sessionId;
        record.userId = row.userId;
        record.data = row.data;
        record.runStartedAt = row.runStartedAt;
        record.createdAt = row.createdAt;
        record.createdBy = row.createdBy;
        record.updatedAt = row.updatedAt;
        record.updatedBy = row.updatedBy;
        return record;
    }

    @Override
    public boolean update(String datasetId, String id, Map<String, Object> patch, String updatedBy) {
        int updated = database.execute("UPDATE " + TABLE + " SET data = dataset.jsonb_merge_patch(data, ?::jsonb), "
                        + "updated_at = ?, updated_by = ? WHERE id = ? AND dataset_id = ?",
                JsonUtil.toJsonWithNulls(patch), ZonedDateTime.now(), updatedBy, id, datasetId);
        return updated > 0;
    }

    @Override
    public boolean delete(String datasetId, String id) {
        return database.execute("DELETE FROM " + TABLE + " WHERE id = ? AND dataset_id = ?", id, datasetId) > 0;
    }

    @Override
    public long deleteByDataset(String datasetId) {
        return database.execute("DELETE FROM " + TABLE + " WHERE dataset_id = ?", datasetId);
    }

    @Override
    public Optional<DatasetRecord> findBySession(String datasetId, String sessionId) {
        return database.selectOne("SELECT " + COLUMNS + " FROM " + TABLE + " WHERE dataset_id = ? AND session_id = ?",
                PgDatasetRecordRow.class, datasetId, sessionId).map(this::toRecord);
    }

    @Override
    public void upsertBySession(String datasetId, String sessionId, String dataJson, String agentId, String userId) {
        DatasetRecordService.checkStateSize(dataJson);
        database.execute(UPSERT_BY_SESSION, sessionParams(datasetId, sessionId, dataJson, agentId, userId));
    }

    @Override
    public void patchBySession(String datasetId, String sessionId, String patchJson, String agentId, String userId) {
        try (Transaction transaction = database.beginTransaction()) {
            var rows = database.select(PATCH_BY_SESSION + " RETURNING octet_length(data::text) AS size",
                    PgSizeRow.class, sessionParams(datasetId, sessionId, patchJson, agentId, userId));
            if (!rows.isEmpty() && rows.getFirst().size != null && rows.getFirst().size > DatasetRecordService.MAX_STATE_BYTES) {
                throw new IllegalArgumentException("state too large, max " + (DatasetRecordService.MAX_STATE_BYTES / 1024) + " KB");
            }
            transaction.commit();
        }
    }

    private Object[] sessionParams(String datasetId, String sessionId, String dataJson, String agentId, String userId) {
        var now = ZonedDateTime.now();
        var effectiveUser = userId != null ? userId : "system";
        var params = new ArrayList<>();
        params.add(UUID.randomUUID().toString());
        params.add(datasetId);
        params.add(agentId);
        params.add(null);
        params.add(sessionId);
        params.add(userId);
        params.add(dataJson);
        params.add(now);
        params.add(now);
        params.add(effectiveUser);
        params.add(now);
        params.add(effectiveUser);
        return params.toArray();
    }

    @Override
    public AggregateResult aggregate(AggregateRequest request) {
        var dataset = datasetMetaStore.get(request.datasetId);
        var sql = DatasetAggregationSql.build(dataset, request);
        var rows = database.select(sql.sql(), PgJsonRow.class, sql.params().toArray());
        var result = new ArrayList<List<Object>>(rows.size());
        for (var row : rows) {
            result.add(normalize(JsonUtil.fromJson(ROW_TYPE, row.payload)));
        }
        return new AggregateResult(sql.columns(), result);
    }

    @Override
    public void onDatasetSchemaChanged(Dataset dataset) {
        viewManager.sync(dataset);
    }

    @Override
    public void onDatasetDeleted(String datasetId) {
        viewManager.drop(datasetId);
    }

    private record ViewSource(String viewName, DatasetFilterSql.Clause clause) {
    }
}
