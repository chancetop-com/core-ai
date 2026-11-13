package ai.core.server.trace;

import ai.core.server.analytics.AnalyticsMappingService;
import ai.core.server.trace.domain.Trace;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import org.bson.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Date;
import java.util.List;
import java.util.Map;

/**
 * The three realtime analytics reads, split out of {@link MongoTraceStore} to keep that file inside the
 * checkstyle length cap. Each returns typed rows so the analytics service no longer reads Documents.
 *
 * @author stephen
 */
class MongoTraceAnalyticsReader {
    private final MongoCollection<Trace> traceCollection;

    MongoTraceAnalyticsReader(MongoCollection<Trace> traceCollection) {
        this.traceCollection = traceCollection;
    }

    List<TraceStatsRow> stats(ZonedDateTime from, ZonedDateTime to, TraceDimension dimension,
                              Map<String, String> modelToProvider, Map<String, String> providerIdToName) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(Filters.gte("started_at", from), Filters.lt("started_at", to))),
            AnalyticsMappingService.buildProviderAddFields(modelToProvider),
            AnalyticsMappingService.buildProviderNameAddFields(providerIdToName),
            Aggregates.group(
                dimension != null ? "$" + dimension.field : null,
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("total_tokens", "$total_tokens"),
                Accumulators.sum("cached_tokens", "$cached_tokens"),
                Accumulators.sum("cost_usd", "$cost_usd"),
                Accumulators.sum("call_count", 1L),
                Accumulators.avg("avg_total_tokens", "$total_tokens"),
                Accumulators.avg("avg_cost_usd", "$cost_usd"),
                Accumulators.max("max_total_tokens", "$total_tokens"),
                Accumulators.max("max_cost_usd", "$cost_usd"),
                // the realtime path has always reported the maximum here
                Accumulators.max("p90_total_tokens", "$total_tokens"),
                Accumulators.first("agent_name", "$agent_name"),
                Accumulators.first("provider_name", "$provider_name")),
            Aggregates.sort(Sorts.descending("total_tokens")));
        return traceCollection.aggregate(aggregate).stream().map(this::statsRow).toList();
    }

    List<TraceTrendRow> trend(ZonedDateTime from, ZonedDateTime to,
                              Map<String, String> modelToProvider, Map<String, String> providerIdToName) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(Filters.gte("started_at", from), Filters.lt("started_at", to))),
            AnalyticsMappingService.buildProviderAddFields(modelToProvider),
            AnalyticsMappingService.buildProviderNameAddFields(providerIdToName),
            Aggregates.group(
                new Document("$dateTrunc", new Document("date", "$started_at").append("unit", "hour")),
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("cached_tokens", "$cached_tokens"),
                Accumulators.sum("cost_usd", "$cost_usd"),
                Accumulators.sum("call_count", 1L)),
            Aggregates.sort(Sorts.ascending("_id")));
        return traceCollection.aggregate(aggregate).stream()
            .map(row -> {
                var value = new TraceTrendRow();
                value.bucket = bucket(row.get("_id"));
                value.inputTokens = longValue(row, "input_tokens");
                value.outputTokens = longValue(row, "output_tokens");
                value.cachedTokens = longValue(row, "cached_tokens");
                value.callCount = longValue(row, "call_count");
                value.costUsd = doubleValue(row, "cost_usd");
                return value;
            })
            .toList();
    }

    List<TraceDimensionTrendRow> dimensionTrend(ZonedDateTime from, ZonedDateTime to, TraceDimension dimension,
                                                List<String> keys, Map<String, String> modelToProvider,
                                                Map<String, String> providerIdToName) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(Filters.gte("started_at", from), Filters.lt("started_at", to))),
            AnalyticsMappingService.buildProviderAddFields(modelToProvider),
            AnalyticsMappingService.buildProviderNameAddFields(providerIdToName),
            Aggregates.group(
                new Document("dim", "$" + dimension.field)
                    .append("hour", new Document("$dateTrunc",
                        new Document("date", "$started_at").append("unit", "hour"))),
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("cached_tokens", "$cached_tokens"),
                Accumulators.sum("cost_usd", "$cost_usd"),
                Accumulators.sum("call_count", 1L)),
            Aggregates.match(Filters.in("_id.dim", keys)),
            Aggregates.sort(Sorts.ascending("_id.hour")));
        return traceCollection.aggregate(aggregate).stream()
            .map(row -> {
                var value = new TraceDimensionTrendRow();
                var group = row.get("_id", Document.class);
                if (group != null) {
                    value.key = group.getString("dim");
                    value.bucket = bucket(group.get("hour"));
                }
                value.inputTokens = longValue(row, "input_tokens");
                value.outputTokens = longValue(row, "output_tokens");
                value.cachedTokens = longValue(row, "cached_tokens");
                value.callCount = longValue(row, "call_count");
                value.costUsd = doubleValue(row, "cost_usd");
                return value;
            })
            .toList();
    }

    private TraceStatsRow statsRow(Document row) {
        var value = new TraceStatsRow();
        var key = row.get("_id");
        value.key = key != null ? key.toString() : null;
        value.agentName = row.getString("agent_name");
        value.providerName = row.getString("provider_name");
        value.inputTokens = longValue(row, "input_tokens");
        value.outputTokens = longValue(row, "output_tokens");
        value.totalTokens = longValue(row, "total_tokens");
        value.cachedTokens = longValue(row, "cached_tokens");
        value.callCount = longValue(row, "call_count");
        value.costUsd = doubleValue(row, "cost_usd");
        value.avgTotalTokens = doubleValue(row, "avg_total_tokens");
        value.avgCostUsd = doubleValue(row, "avg_cost_usd");
        value.maxTotalTokens = longValue(row, "max_total_tokens");
        value.maxCostUsd = doubleValue(row, "max_cost_usd");
        value.p90TotalTokens = doubleValue(row, "p90_total_tokens");
        return value;
    }

    private ZonedDateTime bucket(Object value) {
        if (value instanceof Date date) return ZonedDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
        return null;
    }

    private long longValue(Document row, String key) {
        var value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double doubleValue(Document row, String key) {
        var value = row.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
