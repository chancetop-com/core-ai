package ai.core.server.dataset;

import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * @author stephen
 */
public class DatasetRecordService {
    private static final Logger LOGGER = LoggerFactory.getLogger(DatasetRecordService.class);

    public static final int MAX_STATE_BYTES = 256 * 1024;
    public static final int MAX_PAGE_SIZE = 1000;
    public static final int MAX_FILTER_SCAN_RECORDS = 10_000;

    static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> patch) {
        var result = new LinkedHashMap<>(current);
        for (var entry : patch.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (value == null) {
                result.remove(key);
            } else if (value instanceof Map<?, ?> patchMap && result.get(key) instanceof Map<?, ?> currentMap) {
                @SuppressWarnings("unchecked")
                var merged = merge(new LinkedHashMap<>((Map<String, Object>) currentMap), (Map<String, Object>) patchMap);
                result.put(key, merged);
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    private static boolean matches(DatasetRecord record, Map<String, Object> conditions) {
        Map<String, Object> data = parseData(record);
        if (data == null) return false;
        for (var entry : conditions.entrySet()) {
            Object actual = resolvePath(data, entry.getKey());
            if (!valueEquals(actual, entry.getValue())) return false;
        }
        return true;
    }

    // returns null when data is not a valid JSON object, so callers can skip the record instead of failing the whole query
    private static Map<String, Object> parseData(DatasetRecord record) {
        if (record.data == null || record.data.isBlank()) return Map.of();
        try {
            return JsonUtil.toMap(record.data);
        } catch (RuntimeException | Error e) {
            LOGGER.warn("invalid dataset record data, id={}", record.id, e);
            return null;
        }
    }

    private static Object resolvePath(Map<String, Object> data, String path) {
        Object current = data;
        for (var part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    private static boolean valueEquals(Object actual, Object expected) {
        if (expected == null) return actual == null;
        if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
            // JSON numbers may parse as Integer/Long/Double, compare decimal values exactly
            return new BigDecimal(actualNumber.toString()).compareTo(new BigDecimal(expectedNumber.toString())) == 0;
        }
        return actual != null && actual.equals(expected);
    }

    private static List<DatasetRecord> paginate(List<DatasetRecord> records, int limit, int offset) {
        if (offset >= records.size()) return List.of();
        int end = Math.min(records.size(), offset + limit);
        return records.subList(offset, end);
    }

    private static List<DatasetRecord> trimFields(List<DatasetRecord> records, List<String> fields) {
        if (fields == null || fields.isEmpty()) return records;
        var result = new ArrayList<DatasetRecord>(records.size());
        for (var record : records) {
            Map<String, Object> data = record.data == null || record.data.isBlank() ? null : parseData(record);
            if (data == null) {     // blank or invalid data, keep the record untouched
                result.add(record);
                continue;
            }
            var trimmed = new LinkedHashMap<String, Object>();
            for (var field : fields) {
                var name = field.trim();
                if (name.isEmpty() || !data.containsKey(name)) continue;
                trimmed.put(name, data.get(name));
            }
            record.data = JsonUtil.toJson(trimmed);
            result.add(record);
        }
        return result;
    }

    @Inject
    MongoCollection<DatasetRecord> datasetRecordCollection;

    public void insert(InsertRequest request) {
        var record = new DatasetRecord();
        record.id = UUID.randomUUID().toString();
        record.datasetId = request.datasetId;
        record.agentId = request.agentId;
        record.runId = request.runId;
        record.data = JsonUtil.toJson(request.data);
        record.runStartedAt = request.runStartedAt;
        record.userId = request.userId;
        record.createdBy = request.createdBy;
        record.createdAt = ZonedDateTime.now();
        record.updatedAt = record.createdAt;
        record.updatedBy = request.createdBy;
        datasetRecordCollection.insert(record);
        LOGGER.info("dataset record inserted, datasetId={}, runId={}", request.datasetId, request.runId);
    }

    public QueryResult query(QueryRequest request) {
        var filters = new ArrayList<org.bson.conversions.Bson>();
        filters.add(Filters.eq("dataset_id", request.datasetId));
        if (request.from != null) filters.add(Filters.gte("run_started_at", request.from));
        if (request.to != null) filters.add(Filters.lte("run_started_at", request.to));
        if (request.agentId != null) filters.add(Filters.eq("agent_id", request.agentId));

        var filter = Filters.and(filters);

        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending("run_started_at");

        int limit = request.limit != null ? Math.min(Math.max(request.limit, 1), MAX_PAGE_SIZE) : 100;
        int offset = request.offset != null ? Math.max(request.offset, 0) : 0;

        if (request.filter != null && !request.filter.isEmpty()) {
            // data is stored as a JSON string, field-value matching must happen in memory;
            // cap the scan so one query cannot load an unbounded dataset into memory,
            // truncated=true tells the caller to narrow the time range
            query.limit = MAX_FILTER_SCAN_RECORDS;
            var scanned = datasetRecordCollection.find(query);
            boolean truncated = scanned.size() >= MAX_FILTER_SCAN_RECORDS;
            var matched = scanned.stream()
                    .filter(record -> matches(record, request.filter))
                    .toList();
            return new QueryResult(trimFields(paginate(matched, limit, offset), request.fields), matched.size(), truncated);
        }

        query.limit = limit;
        query.skip = offset;

        var records = datasetRecordCollection.find(query);
        var total = datasetRecordCollection.count(filter);
        return new QueryResult(trimFields(records, request.fields), total, false);
    }

    public boolean update(String id, Map<String, Object> data, String updatedBy) {
        var record = datasetRecordCollection.get(id).orElse(null);
        if (record == null) return false;
        Map<String, Object> merged;
        if (record.data != null && !record.data.isBlank()) {
            merged = merge(JsonUtil.toMap(record.data), data);
        } else {
            merged = new LinkedHashMap<>(data);
        }
        record.data = JsonUtil.toJson(merged);
        record.updatedAt = ZonedDateTime.now();
        record.updatedBy = updatedBy;
        datasetRecordCollection.replace(record);
        LOGGER.info("dataset record updated, id={}", id);
        return true;
    }

    public boolean delete(String id) {
        var record = datasetRecordCollection.get(id).orElse(null);
        if (record == null) return false;
        datasetRecordCollection.delete(id);
        LOGGER.info("dataset record deleted, id={}", id);
        return true;
    }

    public Optional<DatasetRecord> queryBySession(String datasetId, String sessionId) {
        var filter = sessionFilter(datasetId, sessionId);
        return datasetRecordCollection.findOne(filter);
    }

    /**
     * Field-level update: merges the patch into the stored state (RFC 7386 JSON Merge Patch)
     * and writes back. Creates the record from the patch when none exists.
     * Object values merge recursively, arrays and scalars replace entirely, null deletes the field.
     */
    public void patchBySession(String datasetId, String sessionId, String patchJson, String agentId, String userId) {
        var patch = JsonUtil.toMap(patchJson);
        var existing = datasetRecordCollection.findOne(sessionFilter(datasetId, sessionId)).orElse(null);
        Map<String, Object> merged;
        if (existing != null && existing.data != null && !existing.data.isBlank()) {
            merged = merge(JsonUtil.toMap(existing.data), patch);
        } else {
            merged = new LinkedHashMap<>(patch);
        }
        upsertBySession(datasetId, sessionId, JsonUtil.toJson(merged), agentId, userId);
    }

    public void upsertBySession(String datasetId, String sessionId, String dataJson, String agentId, String userId) {
        if (dataJson == null || dataJson.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("state too large, max " + (MAX_STATE_BYTES / 1024) + " KB");
        }
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

    private org.bson.conversions.Bson sessionFilter(String datasetId, String sessionId) {
        return Filters.and(Filters.eq("dataset_id", datasetId), Filters.eq("session_id", sessionId));
    }

    public record QueryResult(List<DatasetRecord> records, long total, boolean truncated) { }

    public record InsertRequest(String datasetId, String agentId, String runId, ZonedDateTime runStartedAt, Map<String, Object> data, String userId, String createdBy) { }
    public record QueryRequest(String datasetId, ZonedDateTime from, ZonedDateTime to, List<String> fields, Integer limit, Integer offset, String agentId, Map<String, Object> filter) { }
}
