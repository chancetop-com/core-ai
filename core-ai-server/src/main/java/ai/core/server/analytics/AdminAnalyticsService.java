package ai.core.server.analytics;

import ai.core.server.domain.GatewayModelConfig;
import ai.core.server.trace.domain.AnalyticsDailyStats;
import ai.core.server.trace.domain.Trace;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.util.ArrayList;
import java.util.LinkedHashMap;
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

    private static long getLong(Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.longValue() : 0L;
    }

    private static double getDouble(Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.doubleValue() : 0.0;
    }

    @Inject
    MongoCollection<AnalyticsDailyStats> analyticsStatsCollection;
    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    MongoCollection<GatewayModelConfig> gatewayModelCollection;

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
        var modelToProvider = loadModelToProviderMapping();
        var rows = aggregateRealtimeTraces(bounds, null, modelToProvider);
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
        var modelToProvider = loadModelToProviderMapping();
        var rows = aggregateRealtimeTracesTrend(bounds, modelToProvider);
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
        List<Document> rows;
        if ("realtime".equals(mode)) {
            var modelToProvider = loadModelToProviderMapping();
            rows = aggregateRealtimeTraces(bounds, dim, modelToProvider);
        } else {
            rows = aggregateStats(bounds, dim);
        }
        var totals = buildGlobalSummary(rows);
        var items = buildDimensionItems(rows, dim, sort);
        return new AnalyticsModels.DimensionAnalytics(items, totals);
    }

    // === Dimension trend ===

    public List<AnalyticsModels.TrendPoint> dimensionTrend(String dimension, String mode, String range, String from, String to, List<String> keys) {
        var dim = Dimension.valueOf(dimension.toUpperCase(Locale.ENGLISH));
        var bounds = AnalyticsDateUtils.resolveDateRange(mode, range, from, to);
        if ("realtime".equals(mode)) {
            return dimensionTrendFromTraces(dim, bounds, keys);
        }
        return dimensionTrendFromStats(dim, bounds, keys);
    }

    // === Shared aggregation helpers ===

    private List<Document> aggregateStats(DateRange bounds, Dimension dim) {
        var match = Aggregates.match(Filters.and(
            Filters.gte("date", bounds.from()),
            Filters.lt("date", bounds.to())
        ));
        var groupField = dim != null ? "$" + dim.field : null;
        var pipeline = buildStatsPipeline(match, groupField, false);
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = pipeline.stream().toList();
        return analyticsStatsCollection.aggregate(aggregate);
    }

    private List<Document> aggregateStatsTrend(DateRange bounds, boolean hourly) {
        var match = Aggregates.match(Filters.and(
            Filters.gte("date", bounds.from()),
            Filters.lt("date", bounds.to())
        ));
        var pipeline = buildTrendPipeline(match, hourly ? "date" : null);
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = pipeline.stream().toList();
        return analyticsStatsCollection.aggregate(aggregate);
    }

    private List<Document> aggregateRealtimeTraces(DateRange bounds, Dimension dim, Map<String, String> modelToProvider) {
        var match = Aggregates.match(Filters.and(
            Filters.gte("started_at", bounds.from()),
            Filters.lt("started_at", bounds.to())
        ));
        var addFields = buildProviderAddFields(modelToProvider);
        String groupField = dim != null ? "$" + dim.field : null;
        var pipeline = buildStatsPipeline(match, groupField, true);
        var list = new ArrayList<Bson>();
        list.add(match);
        list.add(addFields);
        list.addAll(pipeline.subList(1, pipeline.size()));
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = list;
        return traceCollection.aggregate(aggregate);
    }

    private List<Document> aggregateRealtimeTracesTrend(DateRange bounds, Map<String, String> modelToProvider) {
        var match = Aggregates.match(Filters.and(
            Filters.gte("started_at", bounds.from()),
            Filters.lt("started_at", bounds.to())
        ));
        var addFields = buildProviderAddFields(modelToProvider);
        var pipeline = buildTrendPipeline(match, "started_at");
        var list = new ArrayList<Bson>();
        list.add(match);
        list.add(addFields);
        list.addAll(pipeline.subList(1, pipeline.size()));
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = list;
        return traceCollection.aggregate(aggregate);
    }

    private List<Bson> buildStatsPipeline(Bson match, String groupField, boolean realtime) {
        var pipeline = new ArrayList<Bson>();
        pipeline.add(match);
        Object groupId = groupField != null ? groupField : null;
        var group = Aggregates.group(
            groupId,
            Accumulators.sum("input_tokens", realtime ? "$input_tokens" : "$input_tokens"),
            Accumulators.sum("output_tokens", realtime ? "$output_tokens" : "$output_tokens"),
            Accumulators.sum("total_tokens", realtime ? "$total_tokens" : "$total_tokens"),
            Accumulators.sum("cached_tokens", realtime ? "$cached_tokens" : "$cached_tokens"),
            Accumulators.sum("cost_usd", realtime ? "$cost_usd" : "$cost_usd"),
            Accumulators.sum("call_count", realtime ? 1L : "$call_count"),
            Accumulators.avg("avg_total_tokens", realtime ? "$total_tokens" : "$avg_total_tokens"),
            Accumulators.avg("avg_cost_usd", realtime ? "$cost_usd" : "$avg_cost_usd"),
            Accumulators.max("max_total_tokens", realtime ? "$total_tokens" : "$max_total_tokens"),
            Accumulators.max("max_cost_usd", realtime ? "$cost_usd" : "$max_cost_usd"),
            Accumulators.max("p90_total_tokens", realtime ? "$total_tokens" : "$p90_total_tokens")
        );
        pipeline.add(group);
        pipeline.add(Aggregates.sort(Sorts.descending("total_tokens")));
        return pipeline;
    }

    private List<Bson> buildTrendPipeline(Bson match, String dateField) {
        var pipeline = new ArrayList<Bson>();
        pipeline.add(match);
        Document groupId;
        if (dateField != null) {
            groupId = new Document("$dateTrunc",
                new Document("date", "$" + dateField).append("unit", "hour"));
        } else {
            groupId = new Document("date", "$date");
        }
        pipeline.add(Aggregates.group(
            groupId,
            Accumulators.sum("input_tokens", "$input_tokens"),
            Accumulators.sum("output_tokens", "$output_tokens"),
            Accumulators.sum("cached_tokens", "$cached_tokens"),
            Accumulators.sum("cost_usd", "$cost_usd"),
            Accumulators.sum("call_count", "$call_count")
        ));
        pipeline.add(Aggregates.sort(Sorts.ascending("_id")));
        return pipeline;
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

    // === Response builders ===

    private AnalyticsModels.GlobalSummary buildGlobalSummary(List<Document> rows) {
        if (rows.isEmpty()) {
            return new AnalyticsModels.GlobalSummary(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, null, null);
        }
        var row = rows.get(0);
        long inputTokens = getLong(row, "input_tokens");
        long outputTokens = getLong(row, "output_tokens");
        long totalTokens = getLong(row, "total_tokens");
        long cachedTokens = getLong(row, "cached_tokens");
        double costUsd = getDouble(row, "cost_usd");
        long callCount = getLong(row, "call_count");
        double avgTokensPerCall = callCount > 0 ? (double) totalTokens / callCount : 0;
        double avgCostPerCall = callCount > 0 ? costUsd / callCount : 0;
        long maxTokens = getLong(row, "max_total_tokens");
        double maxCost = getDouble(row, "max_cost_usd");
        double p90Tokens = getDouble(row, "p90_total_tokens");
        return new AnalyticsModels.GlobalSummary(
            inputTokens, outputTokens, totalTokens, cachedTokens, costUsd, callCount,
            avgTokensPerCall, avgCostPerCall, maxTokens, maxCost, p90Tokens, null, null
        );
    }

    private List<AnalyticsModels.DimensionItem> buildDimensionItems(List<Document> rows, Dimension dim, String sort) {
        long globalTokens = rows.stream().mapToLong(r -> getLong(r, "total_tokens")).sum();
        double globalCost = rows.stream().mapToDouble(r -> getDouble(r, "cost_usd")).sum();

        var items = new ArrayList<AnalyticsModels.DimensionItem>();
        for (var row : rows) {
            String key = row.getString("_id");
            if (key == null || "1".equals(key)) continue;
            long totalTokens = getLong(row, "total_tokens");
            double costUsd = getDouble(row, "cost_usd");
            items.add(new AnalyticsModels.DimensionItem(
                key, resolveLabel(dim, row), getLong(row, "input_tokens"), getLong(row, "output_tokens"),
                totalTokens, getLong(row, "cached_tokens"), costUsd, getLong(row, "call_count"),
                0, 0, getDouble(row, "avg_total_tokens"), getDouble(row, "avg_cost_usd"),
                getLong(row, "max_total_tokens"), getDouble(row, "max_cost_usd"),
                getDouble(row, "p90_total_tokens"),
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

    private String resolveLabel(Dimension dim, Document row) {
        if (dim == AGENT) {
            return row.getString("_id");
        }
        return row.getString("_id");
    }

    private List<AnalyticsModels.TrendPoint> buildTrendPoints(List<Document> rows) {
        var points = new ArrayList<AnalyticsModels.TrendPoint>();
        for (var row : rows) {
            Object id = row.get("_id");
            String timestamp;
            if (id instanceof String s) {
                timestamp = s;
            } else {
                timestamp = id != null ? id.toString() : "";
            }
            points.add(new AnalyticsModels.TrendPoint(
                timestamp,
                getLong(row, "input_tokens"), getLong(row, "output_tokens"),
                getLong(row, "cached_tokens"), getDouble(row, "cost_usd"),
                getLong(row, "call_count")
            ));
        }
        return points;
    }

    // === Dimension trend ===

    private List<AnalyticsModels.TrendPoint> dimensionTrendFromStats(Dimension dim, DateRange bounds, List<String> keys) {
        var match = Aggregates.match(Filters.and(
            Filters.gte("date", bounds.from()),
            Filters.lt("date", bounds.to()),
            Filters.in(dim.field, keys)
        ));
        var pipeline = new ArrayList<Bson>();
        pipeline.add(match);
        pipeline.add(Aggregates.group(
            new Document("dim", "$" + dim.field).append("date", "$date"),
            Accumulators.sum("input_tokens", "$input_tokens"),
            Accumulators.sum("output_tokens", "$output_tokens"),
            Accumulators.sum("cached_tokens", "$cached_tokens"),
            Accumulators.sum("cost_usd", "$cost_usd"),
            Accumulators.sum("call_count", "$call_count")
        ));
        pipeline.add(Aggregates.sort(Sorts.ascending("_id.date")));
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = pipeline;
        var rows = analyticsStatsCollection.aggregate(aggregate);
        return buildTrendPoints(rows);
    }

    private List<AnalyticsModels.TrendPoint> dimensionTrendFromTraces(Dimension dim, DateRange bounds, List<String> keys) {
        var modelToProvider = loadModelToProviderMapping();
        var match = Aggregates.match(Filters.and(
            Filters.gte("started_at", bounds.from()),
            Filters.lt("started_at", bounds.to())
        ));
        var addFields = buildProviderAddFields(modelToProvider);
        var group = Aggregates.group(
            new Document("dim", "$" + dim.field).append("hour",
                new Document("$dateTrunc",
                    new Document("date", "$started_at").append("unit", "hour"))),
            Accumulators.sum("input_tokens", "$input_tokens"),
            Accumulators.sum("output_tokens", "$output_tokens"),
            Accumulators.sum("cached_tokens", "$cached_tokens"),
            Accumulators.sum("cost_usd", "$cost_usd"),
            Accumulators.sum("call_count", 1L)
        );
        var keyMatch = Aggregates.match(Filters.in("_id.dim", keys));
        var pipeline = List.<Bson>of(match, addFields, group, keyMatch,
            Aggregates.sort(Sorts.ascending("_id.hour")));
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = pipeline;
        var rows = traceCollection.aggregate(aggregate);
        return buildTrendPoints(rows);
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
