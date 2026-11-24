package ai.core.server.report.application;

import ai.core.server.report.domain.ReportConstants;
import ai.core.server.report.domain.ReportSpec;
import ai.core.utils.JsonUtil;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves declared report parameters into a concrete query. Defaults are applied first; an execution-time
 * binding overrides them. Missing values fail with PARAMETER_REQUIRED instead of silently dropping a filter.
 *
 * @author core-ai
 */
final class ReportParameters {
    static Map<String, Object> bindings(ReportSpec spec, Map<String, Object> overrides) {
        var result = new LinkedHashMap<String, Object>();
        for (var parameter : spec.parameters != null ? spec.parameters : List.<ReportSpec.Parameter>of()) {
            if (parameter == null || parameter.name == null) continue;
            var overridden = overrides != null ? overrides.get(parameter.name) : null;
            var value = overridden != null ? overridden : parameter.defaultValue;
            if (value != null) result.put(parameter.name, value);
        }
        if (overrides != null) {
            for (var entry : overrides.entrySet()) {
                if (entry.getKey() != null && entry.getValue() != null) result.put(entry.getKey(), entry.getValue());
            }
        }
        return result;
    }

    static ReportSpec.Query resolve(ReportSpec.Query query, Map<String, Object> bindings) {
        var resolved = JsonUtil.fromJson(ReportSpec.Query.class, JsonUtil.toJson(query));
        if (resolved.timeRange != null && resolved.timeRange.param != null && !resolved.timeRange.param.isBlank()) {
            var value = bindings.get(resolved.timeRange.param);
            if (value == null) throw new ReportQueryException("PARAMETER_REQUIRED", "parameter is required: " + resolved.timeRange.param);
            resolved.timeRange = value instanceof ReportSpec.TimeRange range
                    ? range : JsonUtil.fromJson(ReportSpec.TimeRange.class, JsonUtil.toJson(value));
        }
        if (resolved.filters != null) {
            var iterator = resolved.filters.iterator();
            while (iterator.hasNext()) {
                if (!applyFilter(iterator.next(), bindings)) iterator.remove();
            }
        }
        return resolved;
    }

    private static boolean applyFilter(ReportSpec.Filter filter, Map<String, Object> bindings) {
        if (filter == null || filter.param == null || filter.param.isBlank()) return true;
        var value = bindings.get(filter.param);
        if (value == null) throw new ReportQueryException("PARAMETER_REQUIRED", "parameter is required: " + filter.param);
        if (ReportConstants.FILTER_OP_IN.equals(filter.op)) {
            if (!(value instanceof List<?> values)) {
                throw new ReportQueryException("INVALID_PARAMETER", "IN parameter must be an array: " + filter.param);
            }
            if (values.isEmpty()) return false;
        } else if (value instanceof List<?> || value instanceof Map<?, ?>) {
            throw new ReportQueryException("INVALID_PARAMETER", "scalar parameter required: " + filter.param);
        }
        filter.value = value;
        filter.param = null;
        return true;
    }

    private ReportParameters() {
    }
}
