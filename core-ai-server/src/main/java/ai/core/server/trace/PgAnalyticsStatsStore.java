package ai.core.server.trace;

import ai.core.server.trace.domain.AnalyticsDailyStats;
import ai.core.server.util.JsonSanitizer;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The stored day rows already carry avg/max/P90, so the queries re-aggregate those columns exactly the
 * way the Mongo pipelines did: sum the sums, average the averages and take the max of the maxima.
 *
 * @author stephen
 */
public class PgAnalyticsStatsStore implements AnalyticsStatsStore {
    private static final String TABLE = "app.analytics_daily_stats";
    private static final String INSERT = "INSERT INTO " + TABLE + " (id, user_id, agent_id, agent_name, source, model, "
        + "provider_id, provider_name, date, input_tokens, output_tokens, total_tokens, cached_tokens, cost_usd, "
        + "call_count, avg_input_tokens, avg_output_tokens, avg_total_tokens, avg_cost_usd, max_total_tokens, "
        + "max_cost_usd, p90_total_tokens, p90_cost_usd, session_count) "
        + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
        + "ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, agent_id = EXCLUDED.agent_id, "
        + "agent_name = EXCLUDED.agent_name, source = EXCLUDED.source, model = EXCLUDED.model, "
        + "provider_id = EXCLUDED.provider_id, provider_name = EXCLUDED.provider_name, date = EXCLUDED.date, "
        + "input_tokens = EXCLUDED.input_tokens, output_tokens = EXCLUDED.output_tokens, "
        + "total_tokens = EXCLUDED.total_tokens, cached_tokens = EXCLUDED.cached_tokens, cost_usd = EXCLUDED.cost_usd, "
        + "call_count = EXCLUDED.call_count, avg_input_tokens = EXCLUDED.avg_input_tokens, "
        + "avg_output_tokens = EXCLUDED.avg_output_tokens, avg_total_tokens = EXCLUDED.avg_total_tokens, "
        + "avg_cost_usd = EXCLUDED.avg_cost_usd, max_total_tokens = EXCLUDED.max_total_tokens, "
        + "max_cost_usd = EXCLUDED.max_cost_usd, p90_total_tokens = EXCLUDED.p90_total_tokens, "
        + "p90_cost_usd = EXCLUDED.p90_cost_usd, session_count = EXCLUDED.session_count";
    private static final String STATS_METRICS = ", SUM(COALESCE(input_tokens, 0))::bigint AS input_tokens"
        + ", SUM(COALESCE(output_tokens, 0))::bigint AS output_tokens"
        + ", SUM(COALESCE(total_tokens, 0))::bigint AS total_tokens"
        + ", SUM(COALESCE(cached_tokens, 0))::bigint AS cached_tokens"
        + ", SUM(COALESCE(cost_usd, 0)) AS cost_usd"
        + ", SUM(COALESCE(call_count, 0))::bigint AS call_count"
        + ", AVG(avg_total_tokens)::float8 AS avg_total_tokens"
        + ", AVG(avg_cost_usd)::float8 AS avg_cost_usd"
        + ", MAX(max_total_tokens) AS max_total_tokens"
        + ", MAX(max_cost_usd) AS max_cost_usd"
        + ", MAX(p90_total_tokens) AS p90_total_tokens";
    private static final String TREND_METRICS = ", SUM(COALESCE(input_tokens, 0))::bigint AS input_tokens"
        + ", SUM(COALESCE(output_tokens, 0))::bigint AS output_tokens"
        + ", SUM(COALESCE(cached_tokens, 0))::bigint AS cached_tokens"
        + ", SUM(COALESCE(cost_usd, 0)) AS cost_usd"
        + ", SUM(COALESCE(call_count, 0))::bigint AS call_count";
    private static final String HOUR = "hour";

    @Inject
    Database database;

    @Override
    public void upsert(AnalyticsDailyStats stats) {
        database.execute(INSERT, stats.id, JsonSanitizer.text(stats.userId), JsonSanitizer.text(stats.agentId),
            JsonSanitizer.text(stats.agentName), JsonSanitizer.text(stats.source), JsonSanitizer.text(stats.model),
            JsonSanitizer.text(stats.providerId), JsonSanitizer.text(stats.providerName), stats.date,
            stats.inputTokens, stats.outputTokens, stats.totalTokens, stats.cachedTokens, stats.costUsd, stats.callCount,
            stats.avgInputTokens, stats.avgOutputTokens, stats.avgTotalTokens, stats.avgCostUsd, stats.maxTotalTokens,
            stats.maxCostUsd, stats.p90TotalTokens, stats.p90CostUsd, stats.sessionCount);
    }

    @Override
    public List<TraceStatsRow> stats(ZonedDateTime from, ZonedDateTime to, TraceDimension dimension) {
        var group = dimension == null ? "NULL" : dimension.field;
        var sql = "SELECT " + group + " AS group_value, min(agent_name) AS agent_name, "
            + "min(provider_name) AS provider_name" + STATS_METRICS
            + " FROM " + TABLE + " WHERE date >= ? AND date < ?"
            + (dimension != null ? " GROUP BY 1" : "")
            + " ORDER BY total_tokens DESC";
        return database.select(sql, PgTraceStatsRow.class, from, to).stream().map(this::statsRow).toList();
    }

    @Override
    public List<TraceTrendRow> trend(ZonedDateTime from, ZonedDateTime to, boolean hourly) {
        var sql = "SELECT date_trunc(?, date) AS bucket" + TREND_METRICS
            + " FROM " + TABLE + " WHERE date >= ? AND date < ? GROUP BY 1 ORDER BY 1";
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

    @Override
    public List<TraceDimensionTrendRow> dimensionTrend(TraceDimension dimension, ZonedDateTime from,
                                                       ZonedDateTime to, List<String> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        var params = new ArrayList<Object>();
        params.add(from);
        params.add(to);
        params.addAll(keys);
        var sql = "SELECT " + dimension.field + " AS dim, date AS bucket" + TREND_METRICS
            + " FROM " + TABLE + " WHERE date >= ? AND date < ? AND " + dimension.field
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

    private TraceStatsRow statsRow(PgTraceStatsRow row) {
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
    }
}
