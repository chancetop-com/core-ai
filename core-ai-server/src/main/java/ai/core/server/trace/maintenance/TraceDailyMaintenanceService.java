package ai.core.server.trace.maintenance;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.trace.domain.AnalyticsDailyStats;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceDailyStats;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * @author cyril
 */
public class TraceDailyMaintenanceService {
    private static final Logger LOGGER = LoggerFactory.getLogger(TraceDailyMaintenanceService.class);
    private static final String NO_AGENT = "(no agent)";
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Inject
    MongoCollection<TraceDailyStats> statsCollection;
    @Inject
    MongoCollection<AnalyticsDailyStats> analyticsStatsCollection;
    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;
    @Inject
    MongoCollection<Trace> traceCollection;

    public int aggregateDailyStats(LocalDate date) {
        var dayStart = date.atStartOfDay(UTC);
        var dayEnd = date.plusDays(1).atStartOfDay(UTC);
        var rows = aggregateTraces(dayStart, dayEnd, date);
        int existingStatsCount = upsertStats(rows, date);
        int analyticsCount = aggregateAnalyticsDailyStats(dayStart, dayEnd, date);
        LOGGER.info("daily stats aggregated, date={}, traceDailyStats={}, analyticsDailyStats={}", date, existingStatsCount, analyticsCount);
        return existingStatsCount + analyticsCount;
    }

    private List<Document> aggregateTraces(ZonedDateTime dayStart, ZonedDateTime dayEnd, LocalDate date) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(Filters.gte("started_at", dayStart), Filters.lt("started_at", dayEnd)
            )),
            Aggregates.group(
                new Document()
                    .append("user_id", "$user_id")
                    .append("agent_id", new Document("$ifNull", List.of("$agent_id", NO_AGENT)))
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

    // === Analytics daily stats ===

    private int aggregateAnalyticsDailyStats(ZonedDateTime dayStart, ZonedDateTime dayEnd, LocalDate date) {
        var modelToProvider = loadModelToProviderMapping();
        if (modelToProvider.isEmpty()) {
            LOGGER.info("no gateway_model entries found, skipping analytics aggregation for {}", date);
            return 0;
        }

        var rows = aggregateAnalyticsTraces(dayStart, dayEnd, date, modelToProvider);
        return upsertAnalyticsStats(rows, date);
    }

    private Map<String, String> loadModelToProviderMapping() {
        var models = gatewayModelCollection.find(new Query());
        Map<String, String> mapping = new LinkedHashMap<>();
        for (var model : models) {
            if (model.modelId != null && model.providerId != null) {
                mapping.put(model.modelId, model.providerId);
            }
        }
        return mapping;
    }

    private List<Document> aggregateAnalyticsTraces(ZonedDateTime dayStart, ZonedDateTime dayEnd,
                                                      LocalDate date, Map<String, String> modelToProvider) {
        var addFieldsStage = buildProviderAddFields(modelToProvider);
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(Filters.gte("started_at", dayStart), Filters.lt("started_at", dayEnd))),
            addFieldsStage,
            Aggregates.group(
                new Document()
                    .append("user_id", "$user_id")
                    .append("agent_id", new Document("$ifNull", List.of("$agent_id", NO_AGENT)))
                    .append("agent_name", "$agent_name")
                    .append("source", new Document("$ifNull", List.of("$source", "unknown")))
                    .append("model", new Document("$ifNull", List.of("$model", "unknown")))
                    .append("provider_id", "$provider_id")
                    .append("date", date.toString()),
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
                Accumulators.addToSet("session_ids", "$session_id")
            )
        );
        return traceCollection.aggregate(aggregate);
    }

    private Document buildProviderAddFields(Map<String, String> modelToProvider) {
        var branches = new ArrayList<Document>();
        for (var entry : modelToProvider.entrySet()) {
            branches.add(new Document("case",
                new Document("$eq", List.of("$model", entry.getKey())))
                .append("then", entry.getValue()));
        }
        return new Document("$addFields",
            new Document("provider_id",
                new Document("$switch",
                    new Document("branches", branches)
                        .append("default", "unknown"))));
    }

    private int upsertAnalyticsStats(List<Document> rows, LocalDate date) {
        int created = 0;
        int replaced = 0;

        for (var row : rows) {
            var stats = buildAnalyticsStatsFromRow(row, date);
            if (stats == null) continue;

            if (analyticsStatsCollection.get(stats.id).isPresent()) {
                analyticsStatsCollection.replace(stats);
                replaced++;
            } else {
                analyticsStatsCollection.insert(stats);
                created++;
            }
        }

        LOGGER.info("analytics daily stats aggregated, date={}, total={}, created={}, replaced={}", date, created + replaced, created, replaced);
        return created + replaced;
    }

    private AnalyticsDailyStats buildAnalyticsStatsFromRow(Document row, LocalDate date) {
        var id = row.get("_id", Document.class);
        if (id == null) return null;
        return buildStatsFromId(row, date, id);
    }

    private AnalyticsDailyStats buildStatsFromId(Document row, LocalDate date, Document id) {
        String userId = id.getString("user_id");
        if (userId == null) return null;
        String agentId = id.getString("agent_id");
        if (agentId == null) agentId = NO_AGENT;
        String agentName = id.getString("agent_name");
        String source = id.getString("source");
        if (source == null) source = "unknown";
        String model = id.getString("model");
        if (model == null) model = "unknown";
        String providerId = id.getString("provider_id");
        if (providerId == null) providerId = "unknown";

        var stats = new AnalyticsDailyStats();
        stats.id = userId + "::" + agentId + "::" + source + "::" + model + "::" + providerId + "::" + date;
        stats.userId = userId;
        stats.agentId = agentId;
        stats.agentName = agentName;
        stats.source = source;
        stats.model = model;
        stats.providerId = providerId;
        stats.date = date.atStartOfDay(UTC);

        fillTokenMetrics(stats, row);
        return stats;
    }

    @SuppressWarnings("unchecked")
    private void fillTokenMetrics(AnalyticsDailyStats stats, Document row) {
        stats.inputTokens = TraceMaintenanceHelper.getLong(row, "input_tokens");
        stats.outputTokens = TraceMaintenanceHelper.getLong(row, "output_tokens");
        stats.totalTokens = TraceMaintenanceHelper.getLong(row, "total_tokens");
        stats.cachedTokens = TraceMaintenanceHelper.getLong(row, "cached_tokens");
        stats.costUsd = TraceMaintenanceHelper.getDouble(row, "cost_usd");
        stats.callCount = TraceMaintenanceHelper.getLong(row, "call_count");
        stats.avgInputTokens = TraceMaintenanceHelper.getDouble(row, "avg_input_tokens");
        stats.avgOutputTokens = TraceMaintenanceHelper.getDouble(row, "avg_output_tokens");
        stats.avgTotalTokens = TraceMaintenanceHelper.getDouble(row, "avg_total_tokens");
        stats.avgCostUsd = TraceMaintenanceHelper.getDouble(row, "avg_cost_usd");
        stats.maxTotalTokens = TraceMaintenanceHelper.getLong(row, "max_total_tokens");
        stats.maxCostUsd = TraceMaintenanceHelper.getDouble(row, "max_cost_usd");
        if (stats.totalTokens <= 0) stats.totalTokens = stats.inputTokens + stats.outputTokens;
        var allTotalTokens = (List<Object>) row.get("all_total_tokens");
        var allCostUsd = (List<Object>) row.get("all_cost_usd");
        var sessionIds = (List<String>) row.get("session_ids");
        stats.p90TotalTokens = TraceMaintenanceHelper.computeP90(allTotalTokens);
        stats.p90CostUsd = TraceMaintenanceHelper.computeP90(allCostUsd);
        stats.sessionCount = sessionIds != null ? (long) sessionIds.size() : 0L;
    }

    private int upsertStats(List<Document> rows, LocalDate date) {
        int created = 0;
        int replaced = 0;

        for (var row : rows) {
            var stats = buildStatsFromRow(row, date);
            if (stats == null) continue;

            if (statsCollection.get(stats.id).isPresent()) {
                statsCollection.replace(stats);
                replaced++;
            } else {
                statsCollection.insert(stats);
                created++;
            }
        }

        LOGGER.info("daily stats aggregated, date={}, total={}, created={}, replaced={}", date, created + replaced, created, replaced);
        return created + replaced;
    }

    @SuppressWarnings("unchecked")
    private TraceDailyStats buildStatsFromRow(Document row, LocalDate date) {
        var id = row.get("_id", Document.class);
        if (id == null) return null;
        String userId = id.getString("user_id");
        String agentId = id.getString("agent_id");
        if (userId == null) return null;
        if (agentId == null) agentId = NO_AGENT;

        long inputTokens = TraceMaintenanceHelper.getLong(row, "input_tokens");
        long outputTokens = TraceMaintenanceHelper.getLong(row, "output_tokens");
        long totalTokens = TraceMaintenanceHelper.getLong(row, "total_tokens");
        long cachedTokens = TraceMaintenanceHelper.getLong(row, "cached_tokens");
        double costUsd = TraceMaintenanceHelper.getDouble(row, "cost_usd");
        long callCount = TraceMaintenanceHelper.getLong(row, "call_count");
        double avgTotalTokens = TraceMaintenanceHelper.getDouble(row, "avg_total_tokens");
        double avgCostUsd = TraceMaintenanceHelper.getDouble(row, "avg_cost_usd");
        if (totalTokens <= 0) totalTokens = inputTokens + outputTokens;

        var allTotalTokens = (List<Object>) row.get("all_total_tokens");
        var allCostUsd = (List<Object>) row.get("all_cost_usd");
        var sessionIds = (List<String>) row.get("session_ids");
        double p90TotalTokens = TraceMaintenanceHelper.computeP90(allTotalTokens);
        double p90CostUsd = TraceMaintenanceHelper.computeP90(allCostUsd);
        long sessionCount = sessionIds != null ? sessionIds.size() : 0;

        var stats = new TraceDailyStats();
        stats.id = userId + "_" + agentId + "_" + date;
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
        return stats;
    }
}
