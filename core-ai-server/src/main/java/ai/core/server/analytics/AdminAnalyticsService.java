package ai.core.server.analytics;

import ai.core.server.trace.AnalyticsStatsStore;
import ai.core.server.trace.TraceDimension;
import ai.core.server.trace.TraceDimensionTrendRow;
import ai.core.server.trace.TraceStatsRow;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.TraceTrendRow;
import core.framework.inject.Inject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static ai.core.server.analytics.AnalyticsDateUtils.DateRange;
import static ai.core.server.analytics.AdminAnalyticsService.Dimension.AGENT;
import static ai.core.server.analytics.AdminAnalyticsService.Dimension.MODEL;
import static ai.core.server.analytics.AdminAnalyticsService.Dimension.PROVIDER;
import static ai.core.server.analytics.AdminAnalyticsService.Dimension.SOURCE;
import static ai.core.server.analytics.AdminAnalyticsService.Dimension.USER;


/**
 * Admin analytics query service.
 * History mode queries pre-aggregated {@code analytics_daily_stats},
 * realtime mode queries raw {@code traces} (today only, before archive).
 */
public class AdminAnalyticsService {

    // === Static helpers ===

    private static long value(Long value) {
        return value != null ? value : 0L;
    }

    private static double value(Double value) {
        return value != null ? value : 0.0;
    }
    private static String timestampValue(Object value) {
        if (value instanceof java.util.Date date) return date.toInstant().toString();
        if (value instanceof java.time.ZonedDateTime time) return time.toInstant().toString();
        return value != null ? value.toString() : "";
    }

    @Inject
    AnalyticsStatsStore analyticsStatsStore;
    @Inject
    TraceStore traceStore;
    @Inject
    AnalyticsMappingService mappingService;

    // === Global summary ===

    public AnalyticsModels.GlobalSummary globalSummary(String mode, String range, String from, String to) {
        var bounds = AnalyticsDateUtils.resolveDateRange(mode, range, from, to);
        if ("realtime".equals(mode)) {
            return globalFromTraces(bounds);
        }
        return globalFromStats(bounds);
    }

    private AnalyticsModels.GlobalSummary globalFromStats(DateRange bounds) {
        var rows = aggregateStats(bounds, null);
        var summary = buildGlobalSummary(rows);
        var prevBounds = AnalyticsDateUtils.computePrevRange(bounds);
        AnalyticsModels.GlobalSummary prevSummary = null;
        if (prevBounds != null) {
            var prevRows = aggregateStats(prevBounds, null);
            prevSummary = buildGlobalSummary(prevRows);
        }
        return new AnalyticsModels.GlobalSummary(
            summary.totalInputTokens(), summary.totalOutputTokens(), summary.totalTokens(),
            summary.totalCachedTokens(), summary.totalCostUsd(), summary.totalCalls(),
            summary.avgTokensPerCall(), summary.avgCostPerCall(),
            summary.maxTokensPerCall(), summary.maxCostPerCall(), summary.p90TokensPerCall(),
            prevSummary != null ? prevSummary.totalTokens() : null,
            prevSummary != null ? (Double) prevSummary.totalCostUsd() : null
        );
    }

    private AnalyticsModels.GlobalSummary globalFromTraces(DateRange bounds) {
        var modelToProvider = mappingService.loadModelToProviderMapping();
        var providerIdToName = mappingService.loadProviderIdToNameMapping();
        var rows = aggregateRealtimeTraces(bounds, null, modelToProvider, providerIdToName);
        return buildGlobalSummary(rows);
    }

    // === Trend ===

    public List<AnalyticsModels.TrendPoint> trend(String mode, String range, String from, String to) {
        var bounds = AnalyticsDateUtils.resolveDateRange(mode, range, from, to);
        if ("realtime".equals(mode)) {
            return trendFromTraces(bounds);
        }
        return trendFromStats(bounds);
    }

    private List<AnalyticsModels.TrendPoint> trendFromStats(DateRange bounds) {
        boolean hourly = AnalyticsDateUtils.isHourlyRange(bounds);
        var rows = aggregateStatsTrend(bounds, hourly);
        return buildTrendPoints(rows);
    }

    private List<AnalyticsModels.TrendPoint> trendFromTraces(DateRange bounds) {
        var modelToProvider = mappingService.loadModelToProviderMapping();
        var providerIdToName = mappingService.loadProviderIdToNameMapping();
        var rows = aggregateRealtimeTracesTrend(bounds, modelToProvider, providerIdToName);
        return buildTrendPoints(rows);
    }

    // === Dimension ranking ===

    public AnalyticsModels.DimensionAnalytics bySource(String mode, String range, String from, String to, String sort) {
        return dimensionAnalytics(SOURCE, mode, range, from, to, sort);
    }

    public AnalyticsModels.DimensionAnalytics byAgent(String mode, String range, String from, String to, String sort) {
        return dimensionAnalytics(AGENT, mode, range, from, to, sort);
    }

    public AnalyticsModels.DimensionAnalytics byUser(String mode, String range, String from, String to, String sort) {
        return dimensionAnalytics(USER, mode, range, from, to, sort);
    }

    public AnalyticsModels.DimensionAnalytics byProvider(String mode, String range, String from, String to, String sort) {
        return dimensionAnalytics(PROVIDER, mode, range, from, to, sort);
    }

    public AnalyticsModels.DimensionAnalytics byModel(String mode, String range, String from, String to, String sort) {
        return dimensionAnalytics(MODEL, mode, range, from, to, sort);
    }

    private AnalyticsModels.DimensionAnalytics dimensionAnalytics(Dimension dim, String mode, String range, String from, String to, String sort) {
        var bounds = AnalyticsDateUtils.resolveDateRange(mode, range, from, to);
        List<TraceStatsRow> rows;
        if ("realtime".equals(mode)) {
            var modelToProvider = mappingService.loadModelToProviderMapping();
            var providerIdToName = mappingService.loadProviderIdToNameMapping();
            rows = aggregateRealtimeTraces(bounds, dim, modelToProvider, providerIdToName);
        } else {
            rows = aggregateStats(bounds, dim);
        }
        var totals = buildGlobalSummary(rows);
        Map<String, String> userIdToName = dim == USER ? mappingService.loadUserIdToNameMapping() : Map.of();
        var items = buildDimensionItems(rows, dim, sort, userIdToName);
        return new AnalyticsModels.DimensionAnalytics(items, totals);
    }

    // === Dimension trend ===

    public List<AnalyticsModels.DimensionTrendPoint> dimensionTrend(String dimension, String mode, String range, String from, String to, List<String> keys) {
        var dim = Dimension.valueOf(dimension.toUpperCase(Locale.ENGLISH));
        var bounds = AnalyticsDateUtils.resolveDateRange(mode, range, from, to);
        if ("realtime".equals(mode)) {
            return buildDimensionTrendPoints(dimensionTrendFromTraces(dim, bounds, keys));
        }
        return buildDimensionTrendPoints(dimensionTrendFromStats(dim, bounds, keys));
    }

    // === Shared aggregation helpers ===

    private List<TraceStatsRow> aggregateStats(DateRange bounds, Dimension dim) {
        return analyticsStatsStore.stats(bounds.from(), bounds.to(), dimension(dim));
    }

    private List<TraceTrendRow> aggregateStatsTrend(DateRange bounds, boolean hourly) {
        return analyticsStatsStore.trend(bounds.from(), bounds.to(), hourly);
    }

    private TraceDimension dimension(Dimension dim) {
        return dim != null ? TraceDimension.valueOf(dim.name()) : null;
    }

    private List<TraceStatsRow> aggregateRealtimeTraces(DateRange bounds, Dimension dim, Map<String, String> modelToProvider, Map<String, String> providerIdToName) {
        var dimension = dim != null ? TraceDimension.valueOf(dim.name()) : null;
        return traceStore.realtimeStats(bounds.from(), bounds.to(), dimension, modelToProvider, providerIdToName);
    }

    private List<TraceTrendRow> aggregateRealtimeTracesTrend(DateRange bounds, Map<String, String> modelToProvider, Map<String, String> providerIdToName) {
        return traceStore.realtimeTrend(bounds.from(), bounds.to(), modelToProvider, providerIdToName);
    }

    // === Response builders ===

    private AnalyticsModels.GlobalSummary buildGlobalSummary(List<TraceStatsRow> rows) {
        if (rows.isEmpty()) {
            return new AnalyticsModels.GlobalSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }
        var row = rows.get(0);
        long inputTokens = value(row.inputTokens);
        long outputTokens = value(row.outputTokens);
        long totalTokens = value(row.totalTokens);
        long cachedTokens = value(row.cachedTokens);
        double costUsd = value(row.costUsd);
        long callCount = value(row.callCount);
        double avgTokensPerCall = callCount > 0 ? (double) totalTokens / callCount : 0;
        double avgCostPerCall = callCount > 0 ? costUsd / callCount : 0;
        long maxTokens = value(row.maxTotalTokens);
        double maxCost = value(row.maxCostUsd);
        double p90Tokens = value(row.p90TotalTokens);
        return new AnalyticsModels.GlobalSummary(
            inputTokens, outputTokens, totalTokens, cachedTokens, costUsd, callCount,
            avgTokensPerCall, avgCostPerCall, maxTokens, maxCost, p90Tokens, null, null
        );
    }

    private List<AnalyticsModels.DimensionItem> buildDimensionItems(List<TraceStatsRow> rows, Dimension dim, String sort, Map<String, String> userIdToName) {
        long globalTokens = rows.stream().mapToLong(r -> value(r.totalTokens)).sum();
        double globalCost = rows.stream().mapToDouble(r -> value(r.costUsd)).sum();

        var items = new ArrayList<AnalyticsModels.DimensionItem>();
        for (var row : rows) {
            String key = row.key;
            if (key == null || "1".equals(key)) continue;
            long totalTokens = value(row.totalTokens);
            double costUsd = value(row.costUsd);
            items.add(new AnalyticsModels.DimensionItem(
                key, resolveLabel(dim, row, userIdToName), value(row.inputTokens), value(row.outputTokens),
                totalTokens, value(row.cachedTokens), costUsd, value(row.callCount),
                0, 0, value(row.avgTotalTokens), value(row.avgCostUsd),
                value(row.maxTotalTokens), value(row.maxCostUsd),
                value(row.p90TotalTokens),
                globalTokens > 0 ? (double) totalTokens / globalTokens : 0,
                globalCost > 0 ? costUsd / globalCost : 0
            ));
        }

        items.sort((a, b) -> switch (sort != null ? sort : "tokens") {
            case "cost" -> Double.compare(b.costUsd(), a.costUsd());
            case "calls" -> Long.compare(b.callCount(), a.callCount());
            default -> Long.compare(b.totalTokens(), a.totalTokens());
        });
        return items;
    }

    private String resolveLabel(Dimension dim, TraceStatsRow row, Map<String, String> userIdToName) {
        if (dim == AGENT) {
            String name = row.agentName;
            return name != null ? name : row.key;
        }
        if (dim == PROVIDER) {
            String name = row.providerName;
            return name != null ? name : row.key;
        }
        if (dim == USER) {
            String name = userIdToName.get(row.key);
            return name != null ? name : row.key;
        }
        return row.key;
    }

    private List<AnalyticsModels.TrendPoint> buildTrendPoints(List<TraceTrendRow> rows) {
        var points = new ArrayList<AnalyticsModels.TrendPoint>();
        for (var row : rows) {
            points.add(new AnalyticsModels.TrendPoint(
                timestampValue(row.bucket),
                value(row.inputTokens), value(row.outputTokens),
                value(row.cachedTokens), value(row.costUsd),
                value(row.callCount)
            ));
        }
        return points;
    }

    private List<AnalyticsModels.DimensionTrendPoint> buildDimensionTrendPoints(List<TraceDimensionTrendRow> rows) {
        var points = new ArrayList<AnalyticsModels.DimensionTrendPoint>();
        for (var row : rows) {
            points.add(new AnalyticsModels.DimensionTrendPoint(
                row.key != null ? row.key : "",
                row.bucket != null ? row.bucket.toInstant().toString() : "",
                value(row.inputTokens), value(row.outputTokens),
                value(row.cachedTokens), value(row.costUsd),
                value(row.callCount)
            ));
        }
        return points;
    }

    // === Dimension trend ===

    private List<TraceDimensionTrendRow> dimensionTrendFromStats(Dimension dim, DateRange bounds, List<String> keys) {
        return analyticsStatsStore.dimensionTrend(dimension(dim), bounds.from(), bounds.to(), keys);
    }

    private List<TraceDimensionTrendRow> dimensionTrendFromTraces(Dimension dim, DateRange bounds, List<String> keys) {
        var modelToProvider = mappingService.loadModelToProviderMapping();
        var providerIdToName = mappingService.loadProviderIdToNameMapping();
        return traceStore.realtimeDimensionTrend(bounds.from(), bounds.to(), TraceDimension.valueOf(dim.name()),
            keys, modelToProvider, providerIdToName);
    }

    // === Helpers ===

    enum Dimension {
        SOURCE("source"), AGENT("agent_id"), USER("user_id"), PROVIDER("provider_id"), MODEL("model");
        final String field;

        Dimension(String field) {
            this.field = field;
        }
    }
}
