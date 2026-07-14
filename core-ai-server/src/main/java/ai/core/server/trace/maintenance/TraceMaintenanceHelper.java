package ai.core.server.trace.maintenance;

import ai.core.server.trace.domain.Trace;

import java.util.List;

/**
 * Package-private static helpers for trace maintenance operations.
 *
 * @author stephen
 */
class TraceMaintenanceHelper {

    static long getLong(org.bson.Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.longValue() : 0L;
    }

    static double getDouble(org.bson.Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.doubleValue() : 0.0;
    }

    @SuppressWarnings("unchecked")
    static double computeP90(List<Object> values) {
        if (values == null || values.isEmpty()) return 0.0;
        var nums = values.stream()
            .filter(v -> v instanceof Number)
            .mapToDouble(v -> ((Number) v).doubleValue())
            .sorted()
            .toArray();
        if (nums.length == 0) return 0.0;
        // P90 = value at ceil(0.90 * n) position (1-indexed), i.e. index ceil(0.90*n) - 1
        int idx = (int) Math.ceil(0.90 * nums.length) - 1;
        return nums[Math.max(idx, 0)];
    }

    static List<String> extractTraceIds(List<Trace> traces) {
        return traces.stream()
                .map(t -> t.traceId)
                .filter(id -> id != null && !id.isEmpty())
                .toList();
    }
}
