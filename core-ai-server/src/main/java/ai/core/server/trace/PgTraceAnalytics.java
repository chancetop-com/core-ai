package ai.core.server.trace;

import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The aggregation half of {@link PgTraceStore}. Mongo pushes raw values and computes P90 in Java; here
 * percentile_disc does the same nearest-rank P90 in SQL, and count(distinct) replaces the pushed set.
 *
 * @author stephen
 */
public class PgTraceAnalytics {
    private static final String TABLE = "app.traces";
    private static final String NO_AGENT = "(no agent)";
    private static final String UNKNOWN = "unknown";
    private static final String UTC = "UTC";
    private static final String DAY_FORMAT = "YYYY-MM-DD";
    private static final String HOUR = "hour";

    private static final String SUMS = ", SUM(COALESCE(input_tokens, 0))::bigint AS input_tokens"
        + ", SUM(COALESCE(output_tokens, 0))::bigint AS output_tokens"
        + ", SUM(COALESCE(total_tokens, 0))::bigint AS total_tokens"
        + ", SUM(COALESCE(cached_tokens, 0))::bigint AS cached_tokens"
        + ", SUM(COALESCE(cost_usd, 0)) AS cost_usd";

    private static final String SESSION_COUNT =
        ", COUNT(DISTINCT session_id) + (CASE WHEN COUNT(*) > COUNT(session_id) THEN 1 ELSE 0 END) AS session_count";

    private static final String DAILY_METRICS = SUMS
        + ", COUNT(*) AS call_count"
        + ", AVG(total_tokens)::float8 AS avg_total_tokens"
        + ", AVG(cost_usd)::float8 AS avg_cost_usd"
        + ", percentile_disc(0.9) WITHIN GROUP (ORDER BY total_tokens)::float8 AS p90_total_tokens"
        + ", percentile_disc(0.9) WITHIN GROUP (ORDER BY cost_usd)::float8 AS p90_cost_usd"
        + SESSION_COUNT;

    private static final String ANALYTICS_METRICS = SUMS
        + ", COUNT(*) AS call_count"
        + ", AVG(input_tokens)::float8 AS avg_input_tokens"
        + ", AVG(output_tokens)::float8 AS avg_output_tokens"
        + ", AVG(total_tokens)::float8 AS avg_total_tokens"
        + ", AVG(cost_usd)::float8 AS avg_cost_usd"
        + ", MAX(total_tokens) AS max_total_tokens"
        + ", MAX(cost_usd) AS max_cost_usd"
        + ", percentile_disc(0.9) WITHIN GROUP (ORDER BY total_tokens)::float8 AS p90_total_tokens"
        + ", percentile_disc(0.9) WITHIN GROUP (ORDER BY cost_usd)::float8 AS p90_cost_usd"
        + SESSION_COUNT;

    private static final String STATS_METRICS = SUMS
        + ", COUNT(*) AS call_count"
        + ", AVG(total_tokens)::float8 AS avg_total_tokens"
        + ", AVG(cost_usd)::float8 AS avg_cost_usd"
        + ", MAX(total_tokens) AS max_total_tokens"
        + ", MAX(cost_usd) AS max_cost_usd"
        // the realtime path has always reported the maximum here
        + ", MAX(total_tokens)::float8 AS p90_total_tokens";

    private static final String TREND_METRICS = ", SUM(COALESCE(input_tokens, 0))::bigint AS input_tokens"
        + ", SUM(COALESCE(output_tokens, 0))::bigint AS output_tokens"
        + ", SUM(COALESCE(cached_tokens, 0))::bigint AS cached_tokens"
        + ", SUM(COALESCE(cost_usd, 0)) AS cost_usd"
        + ", COUNT(*) AS call_count";

    @Inject
    Database database;

    List<TraceAggregateRow> aggregate(TraceAggregation aggregation) {
        var filter = PgTraceFilter.of(aggregation.query);
        var params = new ArrayList<Object>();
        var group = groupExpression(aggregation.group, params);
        var sql = "SELECT " + (group != null ? group + " AS group_value" : "NULL AS group_value")
            + ", " + firstExpression(aggregation) + " AS first_value" + SUMS + ", COUNT(*) AS count"
            + " FROM " + TABLE + filter.where()
            + (group != null ? " GROUP BY 1" : "")
            + (aggregation.limit > 0 ? " LIMIT ?" : "");
        params.addAll(filter.paramList());
        if (aggregation.limit > 0) params.add(aggregation.limit);
        return database.select(sql, PgTraceAggregateRow.class, params.toArray()).stream()
            .map(row -> {
                var value = new TraceAggregateRow();
                value.group = row.groupValue;
                value.firstValue = row.firstValue;
                value.inputTokens = row.inputTokens;
                value.outputTokens = row.outputTokens;
                value.totalTokens = row.totalTokens;
                value.cachedTokens = row.cachedTokens;
                value.count = row.count;
                value.costUsd = row.costUsd;
                return value;
            })
            .toList();
    }

    List<TraceDailyRollupRow> dailyRollup(ZonedDateTime from, ZonedDateTime to) {
        var params = new ArrayList<Object>();
        params.add(NO_AGENT);
        var sql = "SELECT user_id AS user_id, COALESCE(agent_id, ?) AS agent_id" + DAILY_METRICS
            + " FROM " + TABLE + " WHERE started_at >= ? AND started_at < ? GROUP BY 1, 2";
        params.add(from);
        params.add(to);
        return database.select(sql, PgTraceRollupRow.class, params.toArray()).stream().map(this::rollupRow).toList();
    }

    List<TraceDailyRollupRow> analyticsRollup(ZonedDateTime from, ZonedDateTime to,
                                              Map<String, String> modelToProvider,
                                              Map<String, String> providerIdToName) {
        var params = new ArrayList<Object>();
        params.add(NO_AGENT);
        params.add(UNKNOWN);
        params.add(UNKNOWN);
        var sql = "SELECT user_id AS user_id, COALESCE(agent_id, ?) AS agent_id"
            + ", agent_name AS agent_name, COALESCE(source, ?) AS source, COALESCE(model, ?) AS model, "
            + providerIdExpression(modelToProvider, params) + " AS provider_id, "
            + providerNameExpression(modelToProvider, providerIdToName, params) + " AS provider_name"
            + ANALYTICS_METRICS
            + " FROM " + TABLE + " WHERE started_at >= ? AND started_at < ? GROUP BY 1, 2, 3, 4, 5, 6, 7";
        params.add(from);
        params.add(to);
        return database.select(sql, PgTraceRollupRow.class, params.toArray()).stream()
            .map(this::rollupRow).toList();
    }

    List<TraceStatsRow> realtimeStats(ZonedDateTime from, ZonedDateTime to, TraceDimension dimension,
                                      Map<String, String> modelToProvider, Map<String, String> providerIdToName) {
        var params = new ArrayList<Object>();
        var group = dimension == null ? "NULL" : dimensionExpression(dimension, modelToProvider, params);
        var sql = "SELECT " + group + " AS group_value, min(agent_name) AS agent_name, min("
            + providerNameExpression(modelToProvider, providerIdToName, params) + ") AS provider_name"
            + STATS_METRICS
            + " FROM " + TABLE + " WHERE started_at >= ? AND started_at < ?"
            + (dimension != null ? " GROUP BY 1" : "")
            + " ORDER BY total_tokens DESC";
        params.add(from);
        params.add(to);
        return database.select(sql, PgTraceStatsRow.class, params.toArray()).stream()
            .map(row -> {
                var value = new TraceStatsRow();
                value.key = row.groupValue;
                value.agentName = row.agentName;
                value.providerName = row.providerName;
                value.inputTokens = row.inputTokens;
                value.outputTokens = row.outputTokens;
                value.totalTokens = row.totalTokens;
                value.cachedTokens = row.cachedTokens;
                value.callCount = row.callCount;
                value.costUsd = row.costUsd;
                value.avgTotalTokens = row.avgTotalTokens;
                value.avgCostUsd = row.avgCostUsd;
                value.maxTotalTokens = row.maxTotalTokens;
                value.maxCostUsd = row.maxCostUsd;
                value.p90TotalTokens = row.p90TotalTokens;
                return value;
            })
            .toList();
    }

    List<TraceTrendRow> realtimeTrend(ZonedDateTime from, ZonedDateTime to,
                                      Map<String, String> modelToProvider, Map<String, String> providerIdToName) {
        var sql = "SELECT date_trunc(?, started_at) AS bucket" + TREND_METRICS
            + " FROM " + TABLE + " WHERE started_at >= ? AND started_at < ? GROUP BY 1 ORDER BY 1";
        return database.select(sql, PgTraceTrendRow.class, HOUR, from, to).stream()
            .map(row -> {
                var value = new TraceTrendRow();
                value.bucket = row.bucket;
                value.inputTokens = row.inputTokens;
                value.outputTokens = row.outputTokens;
                value.cachedTokens = row.cachedTokens;
                value.callCount = row.callCount;
                value.costUsd = row.costUsd;
                return value;
            })
            .toList();
    }

    List<TraceDimensionTrendRow> realtimeDimensionTrend(ZonedDateTime from, ZonedDateTime to,
                                                        TraceDimension dimension, List<String> keys,
                                                        Map<String, String> modelToProvider,
                                                        Map<String, String> providerIdToName) {
        if (keys == null || keys.isEmpty()) return List.of();
        var selectParams = new ArrayList<Object>();
        var dim = dimensionExpression(dimension, modelToProvider, selectParams);
        var whereParams = new ArrayList<Object>();
        var dimWhere = dimensionExpression(dimension, modelToProvider, whereParams);
        var params = new ArrayList<>(selectParams);
        params.add(HOUR);
        params.add(from);
        params.add(to);
        params.addAll(whereParams);
        params.addAll(keys);
        var sql = "SELECT " + dim + " AS dim, date_trunc(?, started_at) AS bucket" + TREND_METRICS
            + " FROM " + TABLE + " WHERE started_at >= ? AND started_at < ? AND " + dimWhere
            + " IN (" + PgTraceFilter.placeholders(keys.size()) + ") GROUP BY 1, 2 ORDER BY 2";
        return database.select(sql, PgTraceDimensionTrendRow.class, params.toArray()).stream()
            .map(row -> {
                var value = new TraceDimensionTrendRow();
                value.key = row.dim;
                value.bucket = row.bucket;
                value.inputTokens = row.inputTokens;
                value.outputTokens = row.outputTokens;
                value.cachedTokens = row.cachedTokens;
                value.callCount = row.callCount;
                value.costUsd = row.costUsd;
                return value;
            })
            .toList();
    }

    private TraceDailyRollupRow rollupRow(PgTraceRollupRow row) {
        var value = new TraceDailyRollupRow();
        value.userId = row.userId;
        value.agentId = row.agentId;
        value.agentName = row.agentName;
        value.source = row.source;
        value.model = row.model;
        value.providerId = row.providerId;
        value.providerName = row.providerName;
        value.inputTokens = row.inputTokens;
        value.outputTokens = row.outputTokens;
        value.totalTokens = row.totalTokens;
        value.cachedTokens = row.cachedTokens;
        value.callCount = row.callCount;
        value.costUsd = row.costUsd;
        value.avgInputTokens = row.avgInputTokens;
        value.avgOutputTokens = row.avgOutputTokens;
        value.avgTotalTokens = row.avgTotalTokens;
        value.avgCostUsd = row.avgCostUsd;
        value.maxTotalTokens = row.maxTotalTokens;
        value.maxCostUsd = row.maxCostUsd;
        value.p90TotalTokens = row.p90TotalTokens;
        value.p90CostUsd = row.p90CostUsd;
        value.sessionCount = row.sessionCount;
        return value;
    }

    private String groupExpression(TraceAggregation.Group group, List<Object> params) {
        return switch (group) {
            case NONE -> null;
            case DAY_BY_CREATED -> {
                params.add(UTC);
                params.add(DAY_FORMAT);
                yield "to_char(created_at AT TIME ZONE ?, ?)";
            }
            case SESSION_ID -> "session_id";
            case TRACE_ID -> "trace_id";
            case AGENT_ID -> "agent_id";
        };
    }

    private String firstExpression(TraceAggregation aggregation) {
        if (aggregation.firstField == null) return "NULL";
        var column = aggregation.firstField == TraceAggregation.FirstField.TRACE_ID ? "trace_id" : "agent_name";
        // Mongo sorts by started_at before grouping; the array_agg ordering does the same in one pass
        return aggregation.latestFirst
            ? "(array_agg(" + column + " ORDER BY started_at DESC))[1]"
            : "min(" + column + ")";
    }

    private String dimensionExpression(TraceDimension dimension, Map<String, String> modelToProvider,
                                       List<Object> params) {
        if (dimension == TraceDimension.PROVIDER) return providerIdExpression(modelToProvider, params);
        return dimension.field;
    }

    private String providerIdExpression(Map<String, String> modelToProvider, List<Object> params) {
        return caseExpression("model", modelToProvider, params);
    }

    private String providerNameExpression(Map<String, String> modelToProvider, Map<String, String> providerIdToName,
                                          List<Object> params) {
        Map<String, String> modelToName = new LinkedHashMap<>(modelToProvider.size());
        for (var entry : modelToProvider.entrySet()) {
            modelToName.put(entry.getKey(), providerIdToName.getOrDefault(entry.getValue(), UNKNOWN));
        }
        return caseExpression("model", modelToName, params);
    }

    private String caseExpression(String column, Map<String, String> mapping, List<Object> params) {
        if (mapping == null || mapping.isEmpty()) {
            params.add(UNKNOWN);
            return "?";
        }
        var sql = new StringBuilder(column.length() + mapping.size() * 16 + 32).append("CASE ").append(column);
        for (var entry : mapping.entrySet()) {
            sql.append(" WHEN ? THEN ?");
            params.add(entry.getKey());
            params.add(entry.getValue());
        }
        sql.append(" ELSE ? END");
        params.add(UNKNOWN);
        return sql.toString();
    }
}
