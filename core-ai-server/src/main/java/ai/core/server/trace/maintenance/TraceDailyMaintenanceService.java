package ai.core.server.trace.maintenance;

import ai.core.server.trace.AnalyticsStatsStore;
import ai.core.server.trace.TraceDailyRollupRow;
import ai.core.server.trace.TraceDailyStatsStore;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.domain.AnalyticsDailyStats;
import ai.core.server.trace.domain.TraceDailyStats;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
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

    private static long value(Long value) {
        return value != null ? value : 0L;
    }

    private static double value(Double value) {
        return value != null ? value : 0.0;
    }
    @Inject
    TraceDailyStatsStore statsStore;
    @Inject
    AnalyticsStatsStore analyticsStatsStore;
    @Inject
    ai.core.server.gateway.GatewayModelStore gatewayModelStore;
    @Inject
    ai.core.server.gateway.GatewayProviderStore gatewayProviderStore;
    @Inject
    TraceStore traceStore;

    public int aggregateDailyStats(LocalDate date) {
        var dayStart = date.atStartOfDay(UTC);
        var dayEnd = date.plusDays(1).atStartOfDay(UTC);
        var rows = traceStore.dailyRollup(dayStart, dayEnd);
        int existingStatsCount = upsertStats(rows, date);
        int analyticsCount = aggregateAnalyticsDailyStats(dayStart, dayEnd, date);
        LOGGER.info("daily stats aggregated, date={}, traceDailyStats={}, analyticsDailyStats={}", date, existingStatsCount, analyticsCount);
        return existingStatsCount + analyticsCount;
    }

    // === Analytics daily stats ===

    private int aggregateAnalyticsDailyStats(ZonedDateTime dayStart, ZonedDateTime dayEnd, LocalDate date) {
        var modelToProvider = loadModelToProviderMapping();
        if (modelToProvider.isEmpty()) {
            LOGGER.info("no gateway_model entries found, skipping analytics aggregation for {}", date);
            return 0;
        }
        var providerIdToName = loadProviderIdToNameMapping();

        var rows = traceStore.analyticsRollup(dayStart, dayEnd, modelToProvider, providerIdToName);
        return upsertAnalyticsStats(rows, date);
    }

    private Map<String, String> loadProviderIdToNameMapping() {
        var providers = gatewayProviderStore.listAll();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (var provider : providers) {
            if (provider.id != null && provider.name != null) {
                mapping.put(provider.id, provider.name);
            }
        }
        return mapping;
    }

    private Map<String, String> loadModelToProviderMapping() {
        var models = gatewayModelStore.listAll();
        Map<String, String> mapping = new LinkedHashMap<>();
        for (var model : models) {
            if (model.modelId != null && model.providerId != null) {
                mapping.put(model.modelId, model.providerId);
            }
        }
        return mapping;
    }

    private int upsertAnalyticsStats(List<TraceDailyRollupRow> rows, LocalDate date) {
        int upserted = 0;
        for (var row : rows) {
            var stats = buildAnalyticsStats(row, date);
            if (stats == null) continue;
            analyticsStatsStore.upsert(stats);
            upserted++;
        }
        LOGGER.info("analytics daily stats aggregated, date={}, total={}", date, upserted);
        return upserted;
    }

    private AnalyticsDailyStats buildAnalyticsStats(TraceDailyRollupRow row, LocalDate date) {
        var userId = row.userId;
        if (userId == null) return null;
        var agentId = row.agentId != null ? row.agentId : NO_AGENT;
        var source = row.source != null ? row.source : "unknown";
        var model = row.model != null ? row.model : "unknown";
        var providerId = row.providerId != null ? row.providerId : "unknown";
        var providerName = row.providerName != null ? row.providerName : "unknown";

        var stats = new AnalyticsDailyStats();
        stats.id = userId + "::" + agentId + "::" + source + "::" + model + "::" + providerId + "::" + date;
        stats.userId = userId;
        stats.agentId = agentId;
        stats.agentName = row.agentName;
        stats.source = source;
        stats.model = model;
        stats.providerId = providerId;
        stats.providerName = providerName;
        stats.date = date.atStartOfDay(UTC);

        stats.inputTokens = value(row.inputTokens);
        stats.outputTokens = value(row.outputTokens);
        stats.totalTokens = value(row.totalTokens);
        stats.cachedTokens = value(row.cachedTokens);
        stats.costUsd = value(row.costUsd);
        stats.callCount = value(row.callCount);
        stats.avgInputTokens = value(row.avgInputTokens);
        stats.avgOutputTokens = value(row.avgOutputTokens);
        stats.avgTotalTokens = value(row.avgTotalTokens);
        stats.avgCostUsd = value(row.avgCostUsd);
        stats.maxTotalTokens = value(row.maxTotalTokens);
        stats.maxCostUsd = value(row.maxCostUsd);
        if (stats.totalTokens <= 0) stats.totalTokens = stats.inputTokens + stats.outputTokens;
        stats.p90TotalTokens = value(row.p90TotalTokens);
        stats.p90CostUsd = value(row.p90CostUsd);
        stats.sessionCount = value(row.sessionCount);
        return stats;
    }

    private int upsertStats(List<TraceDailyRollupRow> rows, LocalDate date) {
        int upserted = 0;
        for (var row : rows) {
            var stats = buildStats(row, date);
            if (stats == null) continue;
            statsStore.upsert(stats);
            upserted++;
        }
        LOGGER.info("daily stats aggregated, date={}, total={}", date, upserted);
        return upserted;
    }

    private TraceDailyStats buildStats(TraceDailyRollupRow row, LocalDate date) {
        var userId = row.userId;
        if (userId == null) return null;
        var agentId = row.agentId != null ? row.agentId : NO_AGENT;

        long inputTokens = value(row.inputTokens);
        long outputTokens = value(row.outputTokens);
        long totalTokens = value(row.totalTokens);
        if (totalTokens <= 0) totalTokens = inputTokens + outputTokens;

        var stats = new TraceDailyStats();
        stats.id = userId + "_" + agentId + "_" + date;
        stats.userId = userId;
        stats.agentId = agentId;
        stats.date = date.atStartOfDay(UTC);
        stats.inputTokens = inputTokens;
        stats.outputTokens = outputTokens;
        stats.totalTokens = totalTokens;
        stats.cachedTokens = value(row.cachedTokens);
        stats.costUsd = value(row.costUsd);
        stats.callCount = value(row.callCount);
        stats.avgTotalTokens = value(row.avgTotalTokens);
        stats.avgCostUsd = value(row.avgCostUsd);
        stats.p90TotalTokens = value(row.p90TotalTokens);
        stats.p90CostUsd = value(row.p90CostUsd);
        stats.sessionCount = value(row.sessionCount);
        return stats;
    }

}
