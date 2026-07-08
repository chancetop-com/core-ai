package ai.core.server.trace.maintenance;

import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceDailyStats;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * Aggregates per-user per-agent daily token/cost stats from the traces collection
 * into trace_daily_stats for fast Dashboard queries.
 *
 * @author cyril
 */
public class TraceDailyMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceDailyMaintenanceService.class);
    private static final String NO_AGENT = "(no agent)";
    private static final ZoneId UTC = ZoneId.of("UTC");

    private static long getLong(org.bson.Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.longValue() : 0L;
    }

    private static double getDouble(org.bson.Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.doubleValue() : 0.0;
    }

    @SuppressWarnings("unchecked")
    private static double getPercentile(org.bson.Document doc, String key) {
        Object value = doc.get(key);
        if (value instanceof List<?> list && !list.isEmpty() && list.get(0) instanceof Number num) {
            return num.doubleValue();
        }
        return 0.0;
    }

    @Inject
    MongoCollection<TraceDailyStats> statsCollection;

    @Inject
    MongoCollection<Trace> traceCollection;

    /**
     * Aggregate stats for a specific date and upsert into trace_daily_stats.
     * Idempotent — safe to re-run.
     *
     * @return number of user-day records written
     */
    public int aggregateDailyStats(LocalDate date) {
        ZonedDateTime dayStart = date.atStartOfDay(UTC);
        ZonedDateTime dayEnd = date.plusDays(1).atStartOfDay(UTC);
        List<org.bson.Document> rows = aggregateTraces(dayStart, dayEnd, date);
        return upsertStats(rows, date);
    }

    private List<org.bson.Document> aggregateTraces(ZonedDateTime dayStart, ZonedDateTime dayEnd, LocalDate date) {
        var aggregate = new Aggregate<org.bson.Document>();
        aggregate.resultClass = org.bson.Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(
                Filters.gte("started_at", dayStart),
                Filters.lt("started_at", dayEnd)
            )),
            Aggregates.group(
                new org.bson.Document()
                    .append("user_id", "$user_id")
                    .append("agent_id", new org.bson.Document("$ifNull", java.util.List.of("$agent_id", NO_AGENT)))
                    .append("date", date.toString()),
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("total_tokens", "$total_tokens"),
                Accumulators.sum("cached_tokens", "$cached_tokens"),
                Accumulators.sum("cost_usd", "$cost_usd"),
                Accumulators.sum("call_count", 1L),
                Accumulators.avg("avg_total_tokens", "$total_tokens"),
                Accumulators.avg("avg_cost_usd", "$cost_usd"),
                // Push token/cost values for P90 computation in Java
                Accumulators.push("all_total_tokens", "$total_tokens"),
                Accumulators.push("all_cost_usd", "$cost_usd"),
                Accumulators.addToSet("session_ids", "$session_id")
            )
        );
        return traceCollection.aggregate(aggregate);
    }

    @SuppressWarnings({"checkstyle:ExecutableStatementCount", "checkstyle:MethodLength"})
    private int upsertStats(List<org.bson.Document> rows, LocalDate date) {
        int created = 0;
        int replaced = 0;

        for (var row : rows) {
            var id = row.get("_id", org.bson.Document.class);
            if (id == null) continue;
            String userId = id.getString("user_id");
            String agentId = id.getString("agent_id");
            if (userId == null) continue;
            if (agentId == null) agentId = NO_AGENT;

            String docId = userId + "_" + agentId + "_" + date;
            long inputTokens = getLong(row, "input_tokens");
            long outputTokens = getLong(row, "output_tokens");
            long totalTokens = getLong(row, "total_tokens");
            long cachedTokens = getLong(row, "cached_tokens");
            double costUsd = getDouble(row, "cost_usd");
            long callCount = getLong(row, "call_count");
            double avgTotalTokens = getDouble(row, "avg_total_tokens");
            double avgCostUsd = getDouble(row, "avg_cost_usd");
            if (totalTokens <= 0) totalTokens = inputTokens + outputTokens;

            @SuppressWarnings("unchecked")
            var allTotalTokens = (List<Object>) row.get("all_total_tokens");
            @SuppressWarnings("unchecked")
            var allCostUsd = (List<Object>) row.get("all_cost_usd");
            @SuppressWarnings("unchecked")
            var sessionIds = (List<String>) row.get("session_ids");
            double p90TotalTokens = computeP90(allTotalTokens);
            double p90CostUsd = computeP90(allCostUsd);
            long sessionCount = sessionIds != null ? sessionIds.size() : 0;

            var existing = statsCollection.get(docId);
            if (existing.isPresent()) {
                var stats = existing.get();
                stats.agentId = agentId;
                stats.date = date.atStartOfDay(UTC);
                stats.inputTokens = inputTokens;
                stats.outputTokens = outputTokens;
                stats.totalTokens = totalTokens;
                stats.cachedTokens = cachedTokens;
                stats.costUsd = costUsd;
                stats.callCount = callCount;
                stats.avgTotalTokens = avgTotalTokens;
                stats.avgCostUsd = avgCostUsd;
                stats.p90TotalTokens = p90TotalTokens;
                stats.p90CostUsd = p90CostUsd;
                stats.sessionCount = sessionCount;
                statsCollection.replace(stats);
                replaced++;
            } else {
                var stats = new TraceDailyStats();
                stats.id = docId;
                stats.userId = userId;
                stats.agentId = agentId;
                stats.date = date.atStartOfDay(UTC);
                stats.inputTokens = inputTokens;
                stats.outputTokens = outputTokens;
                stats.totalTokens = totalTokens;
                stats.cachedTokens = cachedTokens;
                stats.costUsd = costUsd;
                stats.callCount = callCount;
                stats.avgTotalTokens = avgTotalTokens;
                stats.avgCostUsd = avgCostUsd;
                stats.p90TotalTokens = p90TotalTokens;
                stats.p90CostUsd = p90CostUsd;
                stats.sessionCount = sessionCount;
                statsCollection.insert(stats);
                created++;
            }
        }

        LOGGER.info("daily stats aggregated, date={}, total={}, created={}, replaced={}",
                date, created + replaced, created, replaced);
        return created + replaced;
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
}
