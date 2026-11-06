package ai.core.server.dataset.store;

import ai.core.server.dataset.DatasetRecordService;
import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The pre-PoC storage path kept as-is: data lives in a JSON string mapped by core-ng, so filtering happens
 * in memory and writes are read-merge-replace. It is the baseline the other two stores are compared with.
 *
 * @author stephen
 */
public class MongoDatasetRecordStore implements DatasetRecordStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(MongoDatasetRecordStore.class);

    @Inject
    MongoCollection<DatasetRecord> datasetRecordCollection;

    @Override
    public void insert(DatasetRecord record) {
        datasetRecordCollection.insert(record);
    }

    @Override
    public DatasetRecordService.QueryResult query(DatasetRecordService.QueryRequest request) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("dataset_id", request.datasetId()));
        if (request.from() != null) filters.add(Filters.gte("run_started_at", request.from()));
        if (request.to() != null) filters.add(Filters.lte("run_started_at", request.to()));
        if (request.agentId() != null) filters.add(Filters.eq("agent_id", request.agentId()));

        var filter = Filters.and(filters);
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("run_started_at");

        int limit = request.limit() != null ? Math.min(Math.max(request.limit(), 1), DatasetRecordService.MAX_PAGE_SIZE) : 100;
        int offset = request.offset() != null ? Math.max(request.offset(), 0) : 0;

        if (request.filter() != null && !request.filter().isEmpty()) {
            // data is stored as a JSON string, field-value matching must happen in memory;
            // cap the scan so one query cannot load an unbounded dataset into memory,
            // truncated=true tells the caller to narrow the time range
            query.limit = DatasetRecordService.MAX_FILTER_SCAN_RECORDS;
            var scanned = datasetRecordCollection.find(query);
            boolean truncated = scanned.size() >= DatasetRecordService.MAX_FILTER_SCAN_RECORDS;
            var matched = scanned.stream()
                    .filter(record -> DatasetRecordService.matches(record, request.filter()))
                    .toList();
            return new DatasetRecordService.QueryResult(
                    DatasetRecordService.trimFields(DatasetRecordService.paginate(matched, limit, offset), request.fields()),
                    matched.size(), truncated);
        }

        query.limit = limit;
        query.skip = offset;

        var records = datasetRecordCollection.find(query);
        var total = datasetRecordCollection.count(filter);
        return new DatasetRecordService.QueryResult(DatasetRecordService.trimFields(records, request.fields()), total, false);
    }

    @Override
    public boolean update(String datasetId, String id, Map<String, Object> patch, String updatedBy) {
        var record = findRecord(datasetId, id);
        if (record == null) return false;
        Map<String, Object> merged;
        if (record.data != null && !record.data.isBlank()) {
            merged = DatasetRecordService.merge(JsonUtil.toMap(record.data), patch);
        } else {
            merged = new LinkedHashMap<>(patch);
        }
        record.data = JsonUtil.toJson(merged);
        record.updatedAt = ZonedDateTime.now();
        record.updatedBy = updatedBy;
        datasetRecordCollection.replace(record);
        return true;
    }

    @Override
    public boolean delete(String datasetId, String id) {
        var record = findRecord(datasetId, id);
        if (record == null) return false;
        datasetRecordCollection.delete(id);
        return true;
    }

    @Override
    public long deleteByDataset(String datasetId) {
        return datasetRecordCollection.delete(Filters.eq("dataset_id", datasetId));
    }

    @Override
    public long renameField(String datasetId, String from, String to, String updatedBy) {
        long renamed = 0;
        String lastId = "";
        var effectiveUser = updatedBy != null ? updatedBy : "system";
        while (true) {
            var query = new Query();
            query.filter = Filters.and(Filters.eq("dataset_id", datasetId), Filters.gt("_id", lastId));
            query.sort = Sorts.ascending("_id");
            query.limit = 1000;
            var records = datasetRecordCollection.find(query);
            if (records.isEmpty()) return renamed;
            for (var record : records) {
                lastId = record.id;
                if (record.data == null || record.data.isBlank()) continue;
                Map<String, Object> data;
                try {
                    data = JsonUtil.toMap(record.data);
                } catch (RuntimeException | Error e) {
                    LOGGER.warn("skip invalid dataset record while renaming field, id={}", record.id, e);
                    continue;
                }
                if (!data.containsKey(from)) continue;
                var value = data.remove(from);
                data.put(to, value);
                record.data = JsonUtil.toJsonWithNulls(data);
                record.updatedAt = ZonedDateTime.now();
                record.updatedBy = effectiveUser;
                datasetRecordCollection.replace(record);
                renamed++;
            }
            if (records.size() < 1000) return renamed;
        }
    }

    /**
     * A record is addressed by its own id *and* the dataset it belongs to: an id alone would let a caller
     * update or delete a record of a dataset it has no access to (ids are opaque and get copied around).
     */
    private DatasetRecord findRecord(String datasetId, String id) {
        return datasetRecordCollection.findOne(Filters.and(Filters.eq("_id", id), Filters.eq("dataset_id", datasetId)))
                .orElse(null);
    }

    @Override
    public Optional<DatasetRecord> findBySession(String datasetId, String sessionId) {
        return datasetRecordCollection.findOne(sessionFilter(datasetId, sessionId));
    }

    @Override
    public void patchBySession(String datasetId, String sessionId, String patchJson, String agentId, String userId) {
        var patch = JsonUtil.toMap(patchJson);
        var existing = datasetRecordCollection.findOne(sessionFilter(datasetId, sessionId)).orElse(null);
        Map<String, Object> merged;
        if (existing != null && existing.data != null && !existing.data.isBlank()) {
            merged = DatasetRecordService.merge(JsonUtil.toMap(existing.data), patch);
        } else {
            merged = new LinkedHashMap<>(patch);
        }
        upsertBySession(datasetId, sessionId, JsonUtil.toJson(merged), agentId, userId);
    }

    @Override
    public void upsertBySession(String datasetId, String sessionId, String dataJson, String agentId, String userId) {
        DatasetRecordService.checkStateSize(dataJson);
        var filter = sessionFilter(datasetId, sessionId);
        var now = ZonedDateTime.now();
        var effectiveUser = userId != null ? userId : "system";
        var existing = datasetRecordCollection.findOne(filter).orElse(null);
        if (existing != null) {
            existing.data = dataJson;
            existing.runStartedAt = now;
            existing.updatedAt = now;
            existing.updatedBy = effectiveUser;
            datasetRecordCollection.replace(existing);
            LOGGER.info("dataset session state updated, datasetId={}, sessionId={}", datasetId, sessionId);
            return;
        }
        var record = new DatasetRecord();
        record.id = UUID.randomUUID().toString();
        record.datasetId = datasetId;
        record.agentId = agentId;
        record.sessionId = sessionId;
        record.data = dataJson;
        record.runStartedAt = now;
        record.userId = userId;
        record.createdAt = now;
        record.createdBy = effectiveUser;
        record.updatedAt = now;
        record.updatedBy = effectiveUser;
        try {
            datasetRecordCollection.insert(record);
            LOGGER.info("dataset session state created, datasetId={}, sessionId={}", datasetId, sessionId);
        } catch (MongoWriteException e) {
            // concurrent set_session_state inserted the same (dataset_id, session_id) first;
            // retry as update using the winner record id
            if (e.getError().getCode() != 11000) throw e;
            var winner = datasetRecordCollection.findOne(filter).orElse(null);
            if (winner == null) throw e;
            winner.data = dataJson;
            winner.runStartedAt = now;
            winner.updatedAt = now;
            winner.updatedBy = effectiveUser;
            datasetRecordCollection.replace(winner);
            LOGGER.info("dataset session state updated after duplicate key, datasetId={}, sessionId={}", datasetId, sessionId);
        }
    }

    @Override
    public AggregateResult aggregate(AggregateRequest request) {
        throw new UnsupportedOperationException("aggregate is not supported by the core-ng mongo store");
    }

    private Bson sessionFilter(String datasetId, String sessionId) {
        return Filters.and(Filters.eq("dataset_id", datasetId), Filters.eq("session_id", sessionId));
    }
}
