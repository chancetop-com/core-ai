package ai.core.server.dataset.store;

import ai.core.utils.JsonUtil;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Translates the dataset filter JSON (the same shape the tools and the hub already accept) into a SQL
 * predicate over {@code dataset_records.data}. Values and field paths are always bound as parameters;
 * the field path travels as a single string split by {@code string_to_array} because core-ng's database
 * layer cannot bind arrays.
 *
 * @author stephen
 */
public final class DatasetFilterSql {
    private static final String SUPPORTED = "$eq, $ne, $gt, $gte, $lt, $lte, $in, $nin, $exists, $contains";
    private static final String PATH = "data #> string_to_array(?, chr(46))";
    private static final String PATH_TEXT = "data #>> string_to_array(?, chr(46))";

    public static Clause build(Map<String, Object> filter) {
        if (filter == null || filter.isEmpty()) return new Clause(null, List.of());
        var params = new ArrayList<>();
        var parts = new ArrayList<String>();
        for (var entry : filter.entrySet()) {
            parts.add(condition(entry.getKey(), entry.getValue(), params));
        }
        return new Clause(String.join(" AND ", parts), params);
    }

    private static String condition(String path, Object expected, List<Object> params) {
        if (expected == null) {
            params.add(path);
            params.add(path);
            params.add("null");
            return "(" + PATH + " IS NULL OR " + PATH + " = ?::jsonb)";
        }
        if (isOperatorObject(expected)) {
            return operators(path, (Map<?, ?>) expected, params);
        }
        return equality(path, expected, params);
    }

    private static boolean isOperatorObject(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return false;
        for (var key : map.keySet()) {
            if (!(key instanceof String name) || !name.startsWith("$")) return false;
        }
        return true;
    }

    private static String operators(String path, Map<?, ?> operators, List<Object> params) {
        var parts = new ArrayList<String>();
        for (var entry : operators.entrySet()) {
            parts.add(operator(path, (String) entry.getKey(), entry.getValue(), params));
        }
        return "(" + String.join(" AND ", parts) + ")";
    }

    @SuppressFBWarnings("CC_CYCLOMATIC_COMPLEXITY")
    private static String operator(String path, String name, Object expected, List<Object> params) {
        return switch (name) {
            case "$eq" -> condition(path, expected, params);
            case "$ne" -> notEquals(path, expected, params);
            case "$gt" -> compare(path, ">", expected, params);
            case "$gte" -> compare(path, ">=", expected, params);
            case "$lt" -> compare(path, "<", expected, params);
            case "$lte" -> compare(path, "<=", expected, params);
            case "$in" -> in(path, expected, params);
            case "$nin" -> notIn(path, expected, params);
            case "$exists" -> exists(path, expected, params);
            case "$contains" -> contains(path, expected, params);
            default -> throw new IllegalArgumentException("unsupported filter operator " + name + ", supported: " + SUPPORTED);
        };
    }

    // scalars use jsonb containment so the GIN index can serve the predicate; objects and arrays need
    // deep equality, because containment for arrays means "contains element", not "equals"
    private static String equality(String path, Object expected, List<Object> params) {
        if (expected instanceof Map<?, ?> || expected instanceof List<?>) {
            params.add(path);
            params.add(JsonUtil.toJsonWithNulls(expected));
            return PATH + " = ?::jsonb";
        }
        params.add(nestedObject(path, expected));
        return "data @> ?::jsonb";
    }

    private static String nestedObject(String path, Object value) {
        Object current = value;
        var parts = path.split("\\.");
        for (int i = parts.length - 1; i >= 0; i--) {
            var wrapper = new LinkedHashMap<String, Object>();
            wrapper.put(parts[i], current);
            current = wrapper;
        }
        return JsonUtil.toJsonWithNulls(current);
    }

    private static String notEquals(String path, Object expected, List<Object> params) {
        params.add(path);
        if (expected == null) {
            // a missing field and an explicit null are both "null" for the filter semantics
            params.add(path);
            params.add("null");
            return "(" + PATH + " IS NOT NULL AND " + PATH + " <> ?::jsonb)";
        }
        params.add(JsonUtil.toJsonWithNulls(expected));
        return PATH + " IS DISTINCT FROM ?::jsonb";
    }

    private static String compare(String path, String operator, Object expected, List<Object> params) {
        if (expected instanceof Number number) {
            params.add(path);
            params.add("number");
            params.add(path);
            params.add(new BigDecimal(number.toString()));
            return "(CASE WHEN jsonb_typeof(" + PATH + ") = ?::text THEN (" + PATH_TEXT + ")::numeric END) "
                    + operator + " ?";
        }
        if (expected instanceof String text) {
            params.add(path);
            params.add("string");
            params.add(path);
            params.add(text);
            return "(CASE WHEN jsonb_typeof(" + PATH + ") = ?::text THEN " + PATH_TEXT + " END) " + operator + " ?::text";
        }
        throw new IllegalArgumentException("filter comparison requires a number or string value, operator=" + operator);
    }

    @SuppressFBWarnings("MRC_METHOD_RETURNS_CONSTANT")
    private static String in(String path, Object expected, List<Object> params) {
        params.add(path);
        params.add(arrayJson(expected, "$in"));
        return PATH + " IN (SELECT jsonb_array_elements(?::jsonb))";
    }

    @SuppressFBWarnings("MRC_METHOD_RETURNS_CONSTANT")
    private static String notIn(String path, Object expected, List<Object> params) {
        params.add(path);
        params.add(arrayJson(expected, "$nin"));
        return "(" + PATH + " IS NULL OR " + PATH + " NOT IN (SELECT jsonb_array_elements(?::jsonb)))";
    }

    private static String arrayJson(Object expected, String operator) {
        if (!(expected instanceof List<?>)) {
            throw new IllegalArgumentException("filter operator " + operator + " requires an array value");
        }
        return JsonUtil.toJsonWithNulls(expected);
    }

    private static String exists(String path, Object expected, List<Object> params) {
        if (!(expected instanceof Boolean flag)) {
            throw new IllegalArgumentException("filter operator $exists requires a boolean value");
        }
        params.add(path);
        return flag ? PATH + " IS NOT NULL" : PATH + " IS NULL";
    }

    private static String contains(String path, Object expected, List<Object> params) {
        if (!(expected instanceof String text)) {
            throw new IllegalArgumentException("filter operator $contains requires a string value");
        }
        params.add(path);
        params.add("%" + escapeLike(text) + "%");
        return PATH_TEXT + " ILIKE ?";
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private DatasetFilterSql() {
    }

    public record Clause(String sql, List<Object> params) {
    }
}
