package ai.core.server.dataset.store;

import com.mongodb.MongoClientSettings;
import com.mongodb.client.model.Filters;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Builds the aggregation pipeline for the control group. Bucketing uses {@code $dateTrunc} (Mongo 5.0+),
 * LAST relies on a pre-sort by run time, and the result is projected onto fixed c0..cN names so the Java
 * side can normalize it exactly like the SQL result.
 *
 * @author stephen
 */
public final class MongoNativeAggregation {
    private static final Set<String> BUCKETS = Set.of("hour", "day", "week", "month");
    private static final Set<String> FUNCTIONS = Set.of("SUM", "AVG", "MIN", "MAX", "COUNT", "LAST");

    public static Pipeline build(AggregateRequest request) {
        validate(request);
        var timezone = request.timezone != null && !request.timezone.isBlank() ? request.timezone : "UTC";
        var stages = new ArrayList<Bson>();
        stages.add(new Document("$match", match(request)));
        if (usesLast(request)) stages.add(new Document("$sort", new Document("run_started_at", 1)));

        var group = new Document("_id", groupId(request, timezone));
        var columns = new ArrayList<String>();
        columns.add("bucket");
        columns.add("category");
        for (int i = 0; i < request.metrics.size(); i++) {
            var metric = request.metrics.get(i);
            group.append("m" + i, metric(metric));
            columns.add(metricName(metric));
        }
        stages.add(new Document("$group", group));

        var sort = new Document();
        if (request.bucket != null && !request.bucket.isBlank()) sort.append("_id.bucket", 1);
        if (request.categoryField != null && !request.categoryField.isBlank()) sort.append("_id.category", 1);
        if (!sort.isEmpty()) stages.add(new Document("$sort", sort));

        var project = new Document("_id", 0).append("c0", "$_id.bucket").append("c1", "$_id.category");
        for (int i = 0; i < request.metrics.size(); i++) {
            project.append("c" + (i + 2), "$m" + i);
        }
        stages.add(new Document("$project", project));
        return new Pipeline(stages, columns);
    }

    private static Document match(AggregateRequest request) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("dataset_id", request.datasetId));
        if (request.from != null) filters.add(Filters.gte("run_started_at", Date.from(request.from.toInstant())));
        if (request.to != null) filters.add(Filters.lte("run_started_at", Date.from(request.to.toInstant())));
        if (request.agentId != null) filters.add(Filters.eq("agent_id", request.agentId));
        filters.add(MongoNativeFilter.build(request.filter));
        return new Document("$and", filters.stream().map(filter -> filter.toBsonDocument(Document.class,
                MongoClientSettings.getDefaultCodecRegistry())).toList());
    }

    private static Object groupId(AggregateRequest request, String timezone) {
        var hasBucket = request.bucket != null && !request.bucket.isBlank();
        var hasCategory = request.categoryField != null && !request.categoryField.isBlank();
        if (!hasBucket && !hasCategory) return null;
        var id = new Document();
        if (hasBucket) {
            id.append("bucket", new Document("$dateTrunc", new Document("date", "$run_started_at")
                    .append("unit", request.bucket).append("timezone", timezone)));
        }
        if (hasCategory) id.append("category", "$data." + request.categoryField);
        return id;
    }

    private static Object metric(Metric metric) {
        var fn = metric.fn().toUpperCase(Locale.ROOT);
        if ("COUNT".equals(fn)) return new Document("$sum", 1);
        var path = "$data." + metric.field();
        return switch (fn) {
            case "SUM" -> new Document("$sum", path);
            case "AVG" -> new Document("$avg", path);
            case "MIN" -> new Document("$min", path);
            case "MAX" -> new Document("$max", path);
            case "LAST" -> new Document("$last", path);
            default -> throw new IllegalArgumentException("unsupported aggregation function: " + metric.fn());
        };
    }

    private static boolean usesLast(AggregateRequest request) {
        for (var metric : request.metrics) {
            if ("LAST".equalsIgnoreCase(metric.fn())) return true;
        }
        return false;
    }

    private static String metricName(Metric metric) {
        if ("COUNT".equalsIgnoreCase(metric.fn())) return "count";
        return metric.fn().toLowerCase(Locale.ROOT) + "_" + metric.field();
    }

    private static void validate(AggregateRequest request) {
        if (request.datasetId == null) throw new IllegalArgumentException("datasetId is required");
        if (request.metrics == null || request.metrics.isEmpty()) throw new IllegalArgumentException("at least one metric is required");
        if (request.bucket != null && !request.bucket.isBlank() && !BUCKETS.contains(request.bucket)) {
            throw new IllegalArgumentException("bucket must be one of " + BUCKETS + ", value=" + request.bucket);
        }
        for (var metric : request.metrics) {
            if (metric.fn() == null || !FUNCTIONS.contains(metric.fn().toUpperCase(Locale.ROOT))) {
                throw new IllegalArgumentException("unsupported aggregation function: " + metric.fn());
            }
        }
    }

    public static List<Object> normalize(Document document, int metrics) {
        var row = new ArrayList<Object>(metrics + 2);
        row.add(document.getDate("c0") != null ? document.getDate("c0").toInstant().toString() : null);
        row.add(document.get("c1"));
        for (int i = 0; i < metrics; i++) {
            row.add(document.get("c" + (i + 2)));
        }
        return row;
    }

    private MongoNativeAggregation() {
    }

    public record Pipeline(List<Bson> stages, List<String> columns) {
    }
}
