package ai.core.server.dataset;

import ai.core.server.dataset.store.AggregateRequest;
import ai.core.server.dataset.store.AggregateResult;
import ai.core.server.dataset.store.DatasetRecordStore;
import ai.core.server.domain.DatasetRecord;
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
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

    /**
     * Filter text (a JSON object) to conditions. Shared by the tools and the hub so both surfaces accept and
     * reject the same input with the same message; null/blank/"null" means no filter.
     */
    public static Map<String, Object> parseFilter(String filterText) {
        if (filterText == null || filterText.isBlank() || "null".equals(filterText.strip())) return null;
        try {
            return JsonUtil.toMap(filterText);
        } catch (RuntimeException | Error e) {
            throw new IllegalArgumentException("invalid filter, must be a JSON object like {\"status\": \"done\"}: " + e.getMessage(), e);
        }
    }

    /** Record/state payload text (a JSON object) to a map, rejecting everything the tools would never send. */
    public static Map<String, Object> parseData(String dataText) {
        if (dataText == null || dataText.isBlank() || "null".equals(dataText.strip())) {
            throw new IllegalArgumentException("data is required and must not be empty");
        }
        Map<String, Object> data;
        try {
            data = JsonUtil.toMap(dataText);
        } catch (RuntimeException | Error e) {
            throw new IllegalArgumentException("invalid data, must be a JSON object: " + e.getMessage(), e);
        }
        if (data.isEmpty()) throw new IllegalArgumentException("data is required and must not be empty");
        return data;
    }

    // returns null when data is not a valid JSON object, so callers can skip the record instead of failing the whole query
    public static Map<String, Object> parseRecordData(DatasetRecord record) {
        if (record.data == null || record.data.isBlank()) return Map.of();
        try {
            return JsonUtil.toMap(record.data);
        } catch (RuntimeException | Error e) {
            LOGGER.warn("invalid dataset record data, id={}", record.id, e);
            return null;
        }
    }

    public static Map<String, Object> merge(Map<String, Object> current, Map<String, Object> patch) {
        var result = new LinkedHashMap<>(current);
        for (var entry : patch.entrySet()) {
            var key = entry.getKey();
            var value = entry.getValue();
            if (value == null) {
                result.remove(key);
            } else if (value instanceof Map<?, ?> patchMap) {
                @SuppressWarnings("unchecked")
                var nested = (Map<String, Object>) patchMap;
                var currentValue = result.get(key);
                // RFC 7386: an object patch always recurses, even when the target value is not an object,
                // so nulls inside the patch are removals rather than stored values
                @SuppressWarnings("unchecked")
                var target = currentValue instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.<String, Object>of();
                result.put(key, merge(target, nested));
            } else {
                result.put(key, value);
            }
        }
        return result;
    }

    public static boolean matches(DatasetRecord record, Map<String, Object> conditions) {
        Map<String, Object> data = parseRecordData(record);
        if (data == null) return false;
        for (var entry : conditions.entrySet()) {
            Object actual = resolvePath(data, entry.getKey());
            if (!valueEquals(actual, entry.getValue())) return false;
        }
        return true;
    }

    public static Object resolvePath(Map<String, Object> data, String path) {
        Object current = data;
        for (var part : path.split("\\.")) {
            if (!(current instanceof Map<?, ?> map)) return null;
            current = map.get(part);
        }
        return current;
    }

    public static boolean valueEquals(Object actual, Object expected) {
        if (expected == null) return actual == null;
        if (actual instanceof Number actualNumber && expected instanceof Number expectedNumber) {
            // JSON numbers may parse as Integer/Long/Double, compare decimal values exactly
            return new BigDecimal(actualNumber.toString()).compareTo(new BigDecimal(expectedNumber.toString())) == 0;
        }
        return actual != null && actual.equals(expected);
    }

    public static List<DatasetRecord> paginate(List<DatasetRecord> records, int limit, int offset) {
        if (offset >= records.size()) return List.of();
        int end = Math.min(records.size(), offset + limit);
        return records.subList(offset, end);
    }

    public static List<DatasetRecord> trimFields(List<DatasetRecord> records, List<String> fields) {
        if (fields == null || fields.isEmpty()) return records;
        var result = new ArrayList<DatasetRecord>(records.size());
        for (var record : records) {
            Map<String, Object> data = record.data == null || record.data.isBlank() ? null : parseRecordData(record);
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

    public static void checkStateSize(String dataJson) {
        if (dataJson == null || dataJson.getBytes(StandardCharsets.UTF_8).length > MAX_STATE_BYTES) {
            throw new IllegalArgumentException("state too large, max " + (MAX_STATE_BYTES / 1024) + " KB");
        }
    }

    @Inject
    DatasetRecordStore store;

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
        store.insert(record);
        LOGGER.info("dataset record inserted, datasetId={}, runId={}", request.datasetId, request.runId);
    }

    public QueryResult query(QueryRequest request) {
        return store.query(request);
    }

    public boolean update(String datasetId, String id, Map<String, Object> data, String updatedBy) {
        boolean updated = store.update(datasetId, id, data, updatedBy);
        if (updated) LOGGER.info("dataset record updated, id={}", id);
        return updated;
    }

    public boolean delete(String datasetId, String id) {
        boolean deleted = store.delete(datasetId, id);
        if (deleted) LOGGER.info("dataset record deleted, id={}", id);
        return deleted;
    }

    public Optional<DatasetRecord> queryBySession(String datasetId, String sessionId) {
        return store.findBySession(datasetId, sessionId);
    }

    /**
     * Field-level update: merges the patch into the stored state (RFC 7386 JSON Merge Patch).
     * Creates the record from the patch when none exists.
     * Object values merge recursively, arrays and scalars replace entirely, null deletes the field.
     */
    public void patchBySession(String datasetId, String sessionId, String patchJson, String agentId, String userId) {
        store.patchBySession(datasetId, sessionId, patchJson, agentId, userId);
    }

    public void upsertBySession(String datasetId, String sessionId, String dataJson, String agentId, String userId) {
        checkStateSize(dataJson);
        store.upsertBySession(datasetId, sessionId, dataJson, agentId, userId);
    }

    public AggregateResult aggregate(AggregateRequest request) {
        return store.aggregate(request);
    }

    public record QueryResult(List<DatasetRecord> records, long total, boolean truncated) { }

    public record InsertRequest(String datasetId, String agentId, String runId, ZonedDateTime runStartedAt, Map<String, Object> data, String userId, String createdBy) { }

    public record QueryRequest(String datasetId, ZonedDateTime from, ZonedDateTime to, List<String> fields, Integer limit, Integer offset, String agentId, Map<String, Object> filter) { }
}
