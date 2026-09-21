package ai.core.server.dataset;

import ai.core.utils.JsonUtil;

import java.time.format.DateTimeFormatter;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Payload text of the dataset operations, shared by the agent tools and the hub dataset service so both surfaces
 * answer with byte-identical JSON: a script reading a sandbox result and one reading a hub response must not be
 * able to tell which side produced it. Shape and key order are part of the contract — do not reorder.
 *
 * @author stephen
 */
public final class DatasetOpPayloads {
    private static final Object MISSING_FIELD = new Object();

    /**
     * Projects the state to the requested top-level fields. A field absent from the state is omitted, so a caller
     * can distinguish "never set" from "set to null".
     */
    public static Map<String, Object> selectStateFields(Map<String, Object> state, String fields) {
        var selected = new LinkedHashMap<String, Object>();
        for (var field : fields.split(",")) {
            var name = field.trim();
            if (name.isEmpty()) continue;
            var value = state.getOrDefault(name, MISSING_FIELD);
            if (value != MISSING_FIELD) selected.put(name, value);
        }
        return selected;
    }

    /** {@code state} is the whole state, the projected one, or null when the session has no state yet. */
    public static String stateRead(Map<String, Object> state, String datasetId, String sessionId) {
        var response = new LinkedHashMap<String, Object>();
        response.put("state", state);
        response.put("dataset_id", datasetId);
        response.put("session_id", sessionId);
        return JsonUtil.toJson(response);
    }

    public static String stateSaved(String datasetId, String sessionId) {
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "saved");
        response.put("dataset_id", datasetId);
        response.put("session_id", sessionId);
        return JsonUtil.toJson(response);
    }

    public static String stateUpdated(String datasetId, String sessionId, Collection<String> updatedFields) {
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "updated");
        response.put("dataset_id", datasetId);
        response.put("session_id", sessionId);
        response.put("updated_fields", updatedFields);
        return JsonUtil.toJson(response);
    }

    public static String records(String datasetId, DatasetRecordService.QueryResult result) {
        var records = result.records().stream().map(record -> {
            var view = new LinkedHashMap<String, Object>();
            view.put("id", record.id);
            view.put("run_id", record.runId);
            view.put("agent_id", record.agentId);
            view.put("run_started_at", record.runStartedAt != null ? record.runStartedAt.format(DateTimeFormatter.ISO_DATE_TIME) : null);
            view.put("data", record.data != null ? JsonUtil.toMap(record.data) : null);
            return view;
        }).toList();
        var response = new LinkedHashMap<String, Object>();
        response.put("records", records);
        response.put("total", result.total());
        response.put("dataset_id", datasetId);
        if (result.truncated()) {
            response.put("warning", "filter only scanned the most recent " + DatasetRecordService.MAX_FILTER_SCAN_RECORDS
                    + " records, results may be incomplete; narrow the search with from/to time range");
        }
        return JsonUtil.toJson(response);
    }

    public static String recordInserted(String datasetId, Collection<String> fields) {
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "created");
        response.put("dataset_id", datasetId);
        response.put("inserted_fields", fields);
        response.put("message", "record inserted successfully");
        return JsonUtil.toJson(response);
    }

    public static String recordUpdated(String recordId, String datasetId, Collection<String> fields) {
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "updated");
        response.put("record_id", recordId);
        response.put("dataset_id", datasetId);
        response.put("updated_fields", fields);
        response.put("message", "record updated successfully");
        return JsonUtil.toJson(response);
    }

    public static String recordDeleted(String recordId, String datasetId) {
        var response = new LinkedHashMap<String, Object>();
        response.put("status", "deleted");
        response.put("record_id", recordId);
        response.put("dataset_id", datasetId);
        response.put("message", "record deleted successfully");
        return JsonUtil.toJson(response);
    }

    private DatasetOpPayloads() {
    }
}
