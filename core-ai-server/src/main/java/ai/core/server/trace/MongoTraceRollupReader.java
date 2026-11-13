package ai.core.server.trace;

import ai.core.server.analytics.AnalyticsMappingService;
import ai.core.server.trace.domain.Trace;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import org.bson.Document;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

/**
 * The two daily rollups, split out of {@link MongoTraceStore} to keep that file inside the checkstyle
 * length cap. P90 and the distinct session count are computed here because Mongo has no percentile
 * operator; the PostgreSQL store uses percentile_cont and count(distinct) instead.
 *
 * @author stephen
 */
class MongoTraceRollupReader {
    private static final String NO_AGENT = "(no agent)";

    private final MongoCollection<Trace> traceCollection;

    MongoTraceRollupReader(MongoCollection<Trace> traceCollection) {
        this.traceCollection = traceCollection;
    }

    List<TraceDailyRollupRow> daily(ZonedDateTime from, ZonedDateTime to) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(Filters.gte("started_at", from), Filters.lt("started_at", to))),
            Aggregates.group(
                new Document()
                    .append("user_id", "$user_id")
                    .append("agent_id", new Document("$ifNull", List.of("$agent_id", NO_AGENT))),
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("total_tokens", "$total_tokens"),
                Accumulators.sum("cached_tokens", "$cached_tokens"),
                Accumulators.sum("cost_usd", "$cost_usd"),
                Accumulators.sum("call_count", 1L),
                Accumulators.avg("avg_total_tokens", "$total_tokens"),
                Accumulators.avg("avg_cost_usd", "$cost_usd"),
                // pushed so P90 can be computed in Java, matching the SQL percentile later
                Accumulators.push("all_total_tokens", "$total_tokens"),
                Accumulators.push("all_cost_usd", "$cost_usd"),
                Accumulators.addToSet("session_ids", "$session_id")));
        return traceCollection.aggregate(aggregate).stream().map(this::rollupRow).toList();
    }

    List<TraceDailyRollupRow> analytics(ZonedDateTime from, ZonedDateTime to,
                                        Map<String, String> modelToProvider,
                                        Map<String, String> providerIdToName) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(Filters.gte("started_at", from), Filters.lt("started_at", to))),
            AnalyticsMappingService.buildProviderAddFields(modelToProvider),
            AnalyticsMappingService.buildProviderNameAddFields(providerIdToName),
            Aggregates.group(
                new Document()
                    .append("user_id", "$user_id")
                    .append("agent_id", new Document("$ifNull", List.of("$agent_id", NO_AGENT)))
                    .append("agent_name", "$agent_name")
                    .append("source", new Document("$ifNull", List.of("$source", "unknown")))
                    .append("model", new Document("$ifNull", List.of("$model", "unknown")))
                    .append("provider_id", "$provider_id")
                    .append("provider_name", "$provider_name"),
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("total_tokens", "$total_tokens"),
                Accumulators.sum("cached_tokens", "$cached_tokens"),
                Accumulators.sum("cost_usd", "$cost_usd"),
                Accumulators.sum("call_count", 1L),
                Accumulators.avg("avg_input_tokens", "$input_tokens"),
                Accumulators.avg("avg_output_tokens", "$output_tokens"),
                Accumulators.avg("avg_total_tokens", "$total_tokens"),
                Accumulators.avg("avg_cost_usd", "$cost_usd"),
                Accumulators.max("max_total_tokens", "$total_tokens"),
                Accumulators.max("max_cost_usd", "$cost_usd"),
                Accumulators.push("all_total_tokens", "$total_tokens"),
                Accumulators.push("all_cost_usd", "$cost_usd"),
                Accumulators.addToSet("session_ids", "$session_id")));
        return traceCollection.aggregate(aggregate).stream().map(this::rollupRow).toList();
    }

    private TraceDailyRollupRow rollupRow(Document row) {
        var value = new TraceDailyRollupRow();
        var group = row.get("_id", Document.class);
        if (group != null) {
            value.userId = group.getString("user_id");
            value.agentId = group.getString("agent_id");
            value.agentName = group.getString("agent_name");
            value.source = group.getString("source");
            value.model = group.getString("model");
            value.providerId = group.getString("provider_id");
            value.providerName = group.getString("provider_name");
        }
        value.inputTokens = longValue(row, "input_tokens");
        value.outputTokens = longValue(row, "output_tokens");
        value.totalTokens = longValue(row, "total_tokens");
        value.cachedTokens = longValue(row, "cached_tokens");
        value.callCount = longValue(row, "call_count");
        value.costUsd = doubleValue(row, "cost_usd");
        value.avgInputTokens = doubleValue(row, "avg_input_tokens");
        value.avgOutputTokens = doubleValue(row, "avg_output_tokens");
        value.avgTotalTokens = doubleValue(row, "avg_total_tokens");
        value.avgCostUsd = doubleValue(row, "avg_cost_usd");
        value.maxTotalTokens = longValue(row, "max_total_tokens");
        value.maxCostUsd = doubleValue(row, "max_cost_usd");

        var totalTokens = row.getList("all_total_tokens", Object.class);
        var costUsd = row.getList("all_cost_usd", Object.class);
        value.p90TotalTokens = p90(totalTokens);
        value.p90CostUsd = p90(costUsd);

        var sessions = row.getList("session_ids", String.class);
        value.sessionCount = sessions == null ? 0L : (long) sessions.size();
        return value;
    }

    private List<Double> numbers(List<Object> values) {
        if (values == null) return List.of();
        return values.stream().filter(Number.class::isInstance).map(v -> ((Number) v).doubleValue()).toList();
    }

    private double p90(List<Object> values) {
        var nums = numbers(values).stream().sorted().toList();
        if (nums.isEmpty()) return 0.0;
        int index = (int) Math.ceil(0.90 * nums.size()) - 1;
        return nums.get(Math.max(index, 0));
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
