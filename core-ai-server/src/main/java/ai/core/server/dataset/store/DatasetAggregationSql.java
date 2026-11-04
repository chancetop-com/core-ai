package ai.core.server.dataset.store;

import ai.core.server.domain.Dataset;
import ai.core.server.domain.SchemaFieldType;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Builds the aggregation SQL. Grouping keys, bucket unit and timezone are all bound parameters; the
 * result is serialized with {@code jsonb_build_array} so one String column carries a variable number of
 * metrics, which is the only shape a core-ng view can map.
 *
 * @author stephen
 */
public final class DatasetAggregationSql {
    private static final Set<String> BUCKETS = Set.of("hour", "day", "week", "month");
    private static final Set<String> FUNCTIONS = Set.of("SUM", "AVG", "MIN", "MAX", "COUNT", "LAST");

    public static Sql build(Dataset dataset, AggregateRequest request) {
        var schema = schema(dataset);
        validate(request, schema);
        var timezone = request.timezone != null && !request.timezone.isBlank() ? request.timezone : "UTC";
        ZoneId.of(timezone);

        var params = new ArrayList<>();
        var selects = new ArrayList<String>();
        var groupBy = new ArrayList<String>();
        selects.add(bucketExpression(request, timezone, params, groupBy));
        selects.add(categoryExpression(request, schema, params, groupBy));

        var columns = new ArrayList<String>();
        columns.add("bucket");
        columns.add("category");
        for (var metric : request.metrics) {
            selects.add(metricExpression(metric, params));
            columns.add(metricName(metric));
        }

        var where = where(request, params);
        var sql = new StringBuilder(512).append("SELECT jsonb_build_array(");
        for (int i = 0; i < selects.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append("t.c").append(i);
        }
        sql.append(")::text AS payload FROM (SELECT ");
        for (int i = 0; i < selects.size(); i++) {
            if (i > 0) sql.append(", ");
            sql.append(selects.get(i)).append(" AS c").append(i);
        }
        sql.append(" FROM dataset_records WHERE ").append(where);
        if (!groupBy.isEmpty()) sql.append(" GROUP BY ").append(String.join(", ", groupBy));
        sql.append(") t ORDER BY t.c0, t.c1");
        return new Sql(sql.toString(), params, columns);
    }

    private static String bucketExpression(AggregateRequest request, String timezone, List<Object> params, List<String> groupBy) {
        if (request.bucket == null || request.bucket.isBlank()) return "NULL";
        params.add(request.bucket);
        params.add(timezone);
        groupBy.add("1");
        return "date_trunc(?::text, run_started_at, ?::text)";
    }

    private static String categoryExpression(AggregateRequest request, Map<String, SchemaFieldType> schema, List<Object> params, List<String> groupBy) {
        if (request.categoryField == null || request.categoryField.isBlank()) return "NULL";
        if (!schema.containsKey(request.categoryField)) {
            throw new IllegalArgumentException("unknown category field: " + request.categoryField);
        }
        params.add(request.categoryField);
        groupBy.add("2");
        return "data #>> string_to_array(?, chr(46))";
    }

    private static String metricExpression(Metric metric, List<Object> params) {
        var fn = metric.fn() == null ? "" : metric.fn().toUpperCase(Locale.ROOT);
        if ("COUNT".equals(fn)) return "count(*)";
        if ("LAST".equals(fn)) {
            params.add(metric.field());
            return "(array_agg(data #> string_to_array(?, chr(46)) ORDER BY run_started_at DESC))[1]";
        }
        if ("SUM".equals(fn) || "AVG".equals(fn) || "MIN".equals(fn) || "MAX".equals(fn)) {
            params.add(metric.field());
            params.add("number");
            params.add(metric.field());
            return fn.toLowerCase(Locale.ROOT) + "((CASE WHEN jsonb_typeof(data #> string_to_array(?, chr(46))) = ?::text"
                    + " THEN (data #>> string_to_array(?, chr(46)))::numeric END))";
        }
        throw new IllegalArgumentException("unsupported aggregation function: " + metric.fn());
    }

    private static String where(AggregateRequest request, List<Object> params) {
        var where = new StringBuilder(128).append("dataset_id = ?");
        params.add(request.datasetId);
        if (request.from != null) {
            where.append(" AND run_started_at >= ?");
            params.add(request.from);
        }
        if (request.to != null) {
            where.append(" AND run_started_at <= ?");
            params.add(request.to);
        }
        if (request.agentId != null) {
            where.append(" AND agent_id = ?");
            params.add(request.agentId);
        }
        var filter = DatasetFilterSql.build(request.filter);
        if (filter.sql() != null) where.append(" AND ").append(filter.sql());
        params.addAll(filter.params());
        return where.toString();
    }

    private static String metricName(Metric metric) {
        if ("COUNT".equalsIgnoreCase(metric.fn())) return "count";
        return (metric.fn() == null ? "" : metric.fn().toLowerCase(Locale.ROOT)) + "_" + metric.field();
    }

    private static Map<String, SchemaFieldType> schema(Dataset dataset) {
        var schema = new LinkedHashMap<String, SchemaFieldType>();
        if (dataset == null || dataset.schema == null) return schema;
        for (var field : dataset.schema) {
            if (field != null && field.name != null) schema.put(field.name, field.type != null ? field.type : SchemaFieldType.STRING);
        }
        return schema;
    }

    private static void validate(AggregateRequest request, Map<String, SchemaFieldType> schema) {
        if (request.datasetId == null) throw new IllegalArgumentException("datasetId is required");
        if (request.metrics == null || request.metrics.isEmpty()) throw new IllegalArgumentException("at least one metric is required");
        if (request.bucket != null && !request.bucket.isBlank() && !BUCKETS.contains(request.bucket)) {
            throw new IllegalArgumentException("bucket must be one of " + BUCKETS + ", value=" + request.bucket);
        }
        for (var metric : request.metrics) {
            validateMetric(metric, schema);
        }
    }

    private static void validateMetric(Metric metric, Map<String, SchemaFieldType> schema) {
        var fn = metric.fn() == null ? "" : metric.fn().toUpperCase(Locale.ROOT);
        if (!FUNCTIONS.contains(fn)) throw new IllegalArgumentException("unsupported aggregation function: " + metric.fn());
        if ("COUNT".equals(fn)) return;
        var type = schema.get(metric.field());
        if (type == null) throw new IllegalArgumentException("unknown metric field: " + metric.field());
        if (type != SchemaFieldType.NUMBER && !"LAST".equals(fn)) {
            throw new IllegalArgumentException("function " + fn + " requires a NUMBER field: " + metric.field());
        }
    }

    private DatasetAggregationSql() {
    }

    public record Sql(String sql, List<Object> params, List<String> columns) {
    }
}
