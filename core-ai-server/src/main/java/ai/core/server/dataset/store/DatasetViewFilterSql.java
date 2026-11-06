package ai.core.server.dataset.store;

import ai.core.server.domain.SchemaFieldType;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Builds predicates over the generated dataset view, where every schema field is already a typed column.
 * Only simple schema fields are handled; anything else (nested paths, objects, {@code $exists}) returns
 * null so the caller falls back to the jsonb predicate on the base table.
 *
 * @author stephen
 */
final class DatasetViewFilterSql {
    private static final Set<String> COMPARISONS = Set.of("$gt", "$gte", "$lt", "$lte");

    static DatasetFilterSql.Clause build(Map<String, Object> filter, Map<String, DatasetViewManager.Column> columns) {
        if (filter == null || filter.isEmpty()) return null;
        var params = new ArrayList<>();
        var parts = new ArrayList<String>();
        for (var entry : filter.entrySet()) {
            var column = columns.get(entry.getKey());
            if (column == null) return null;
            var predicate = condition(column, entry.getValue(), params);
            if (predicate == null) return null;
            parts.add(predicate);
        }
        return new DatasetFilterSql.Clause(String.join(" AND ", parts), params);
    }

    private static String condition(DatasetViewManager.Column column, Object expected, List<Object> params) {
        var ref = DatasetViewManager.quoteIdentifier(column.columnName());
        // a view column is NULL for a missing field, an explicit jsonb null and a type mismatch alike;
        // the jsonb path distinguishes them, so null conditions keep using it
        if (expected == null) return null;
        if (isOperatorObject(expected)) {
            var parts = new ArrayList<String>();
            for (var entry : ((Map<?, ?>) expected).entrySet()) {
                var predicate = operator(column, ref, (String) entry.getKey(), entry.getValue(), params);
                if (predicate == null) return null;
                parts.add(predicate);
            }
            return "(" + String.join(" AND ", parts) + ")";
        }
        if (expected instanceof Map<?, ?> || expected instanceof List<?>) return null;
        var placeholder = placeholder(column.type(), expected, params);
        return placeholder != null ? ref + " = " + placeholder : null;
    }

    private static boolean isOperatorObject(Object value) {
        if (!(value instanceof Map<?, ?> map) || map.isEmpty()) return false;
        for (var key : map.keySet()) {
            if (!(key instanceof String name) || !name.startsWith("$")) return false;
        }
        return true;
    }

    private static String operator(DatasetViewManager.Column column, String ref, String name, Object expected, List<Object> params) {
        if ("$eq".equals(name)) return condition(column, expected, params);
        if ("$exists".equals(name)) return null;      // a jsonb null and a missing field collapse into the same view NULL
        if ("$ne".equals(name)) {
            if (expected == null) return null;
            if (expected instanceof Map<?, ?> || expected instanceof List<?>) return null;
            var placeholder = placeholder(column.type(), expected, params);
            return placeholder != null ? ref + " IS DISTINCT FROM " + placeholder : null;
        }
        if (COMPARISONS.contains(name)) return comparison(column, ref, name, expected, params);
        if ("$in".equals(name) || "$nin".equals(name)) return in(column, ref, name, expected, params);
        if ("$contains".equals(name) && column.type() == SchemaFieldType.STRING) {
            params.add("%" + escapeLike(String.valueOf(expected)) + "%");
            return ref + " ILIKE ?";
        }
        return null;
    }

    private static String comparison(DatasetViewManager.Column column, String ref, String name, Object expected, List<Object> params) {
        var operator = switch (name) {
            case "$gt" -> ">";
            case "$gte" -> ">=";
            case "$lt" -> "<";
            default -> "<=";
        };
        if (column.type() == SchemaFieldType.NUMBER && expected instanceof Number) {
            params.add(decimal(expected));
            return ref + " " + operator + " ?::numeric";
        }
        if (column.type() == SchemaFieldType.STRING && expected instanceof String) {
            params.add(expected);
            return ref + " " + operator + " ?::text";
        }
        if (column.type() == SchemaFieldType.DATE && expected instanceof String) {
            params.add(expected);
            return ref + " " + operator + " ?::date";
        }
        if (column.type() == SchemaFieldType.DATETIME && expected instanceof String) {
            params.add(expected);
            return ref + " " + operator + " ?::timestamptz";
        }
        return null;
    }

    private static String in(DatasetViewManager.Column column, String ref, String name, Object expected, List<Object> params) {
        if (!(expected instanceof List<?> values) || values.isEmpty()) return null;
        var placeholders = new ArrayList<String>(values.size());
        for (var value : values) {
            if (value == null) return null;
            var placeholder = placeholder(column.type(), value, params);
            if (placeholder == null) return null;
            placeholders.add(placeholder);
        }
        var predicate = ref + " IN (" + String.join(", ", placeholders) + ")";
        return "$nin".equals(name) ? "(" + ref + " IS NULL OR NOT (" + predicate + "))" : predicate;
    }

    private static String placeholder(SchemaFieldType type, Object value, List<Object> params) {
        return switch (type) {
            case NUMBER -> {
                if (!(value instanceof Number)) yield null;
                params.add(decimal(value));
                yield "?::numeric";
            }
            case BOOLEAN -> {
                if (!(value instanceof Boolean)) yield null;
                params.add(value);
                yield "?";
            }
            case STRING -> {
                if (!(value instanceof String)) yield null;
                params.add(value);
                yield "?::text";
            }
            case DATE -> {
                if (!(value instanceof String)) yield null;
                params.add(value);
                yield "?::date";
            }
            case DATETIME -> {
                if (!(value instanceof String)) yield null;
                params.add(value);
                yield "?::timestamptz";
            }
        };
    }

    private static BigDecimal decimal(Object value) {
        return new BigDecimal(String.valueOf(value));
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    private DatasetViewFilterSql() {
    }
}
