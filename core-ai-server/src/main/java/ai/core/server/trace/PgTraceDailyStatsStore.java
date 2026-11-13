package ai.core.server.trace;

import ai.core.server.trace.domain.TraceDailyStats;
import ai.core.server.util.JsonSanitizer;
import core.framework.db.Database;
import core.framework.inject.Inject;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * @author stephen
 */
public class PgTraceDailyStatsStore implements TraceDailyStatsStore {
    private static final String TABLE = "app.trace_daily_stats";
    private static final String UPSERT = "INSERT INTO " + TABLE + " (id, user_id, agent_id, date, input_tokens, "
        + "output_tokens, total_tokens, cached_tokens, cost_usd, call_count, avg_total_tokens, avg_cost_usd, "
        + "p90_total_tokens, p90_cost_usd, session_count) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
        + "ON CONFLICT (id) DO UPDATE SET user_id = EXCLUDED.user_id, agent_id = EXCLUDED.agent_id, "
        + "date = EXCLUDED.date, input_tokens = EXCLUDED.input_tokens, output_tokens = EXCLUDED.output_tokens, "
        + "total_tokens = EXCLUDED.total_tokens, cached_tokens = EXCLUDED.cached_tokens, cost_usd = EXCLUDED.cost_usd, "
        + "call_count = EXCLUDED.call_count, avg_total_tokens = EXCLUDED.avg_total_tokens, "
        + "avg_cost_usd = EXCLUDED.avg_cost_usd, p90_total_tokens = EXCLUDED.p90_total_tokens, "
        + "p90_cost_usd = EXCLUDED.p90_cost_usd, session_count = EXCLUDED.session_count";
    private static final String DAILY_USAGE = "SELECT date, SUM(COALESCE(input_tokens, 0))::bigint AS input_tokens, "
        + "SUM(COALESCE(output_tokens, 0))::bigint AS output_tokens, "
        + "SUM(COALESCE(total_tokens, 0))::bigint AS total_tokens, "
        + "SUM(COALESCE(cached_tokens, 0))::bigint AS cached_tokens, "
        + "SUM(COALESCE(cost_usd, 0)) AS cost_usd FROM " + TABLE
        + " WHERE user_id = ? AND date >= ? AND date <= ? GROUP BY 1 ORDER BY 1";

    @Inject
    Database database;

    @Override
    public void upsert(TraceDailyStats stats) {
        database.execute(UPSERT, stats.id, JsonSanitizer.text(stats.userId), JsonSanitizer.text(stats.agentId),
            stats.date, stats.inputTokens, stats.outputTokens, stats.totalTokens, stats.cachedTokens, stats.costUsd,
            stats.callCount, stats.avgTotalTokens, stats.avgCostUsd, stats.p90TotalTokens, stats.p90CostUsd,
            stats.sessionCount);
    }

    @Override
    public long countByDate(ZonedDateTime dayStart) {
        var row = database.selectOne("SELECT count(*) AS count FROM " + TABLE + " WHERE date = ?",
            PgTraceCountRow.class, dayStart).orElse(null);
        return row == null || row.count == null ? 0 : row.count;
    }

    @Override
    public List<TraceDailyUsageRow> dailyUsage(String userId, ZonedDateTime from, ZonedDateTime to) {
        return database.select(DAILY_USAGE, PgTraceDailyUsageRow.class, userId, from, to).stream()
            .map(row -> {
                var value = new TraceDailyUsageRow();
                value.date = row.date;
                value.inputTokens = row.inputTokens;
                value.outputTokens = row.outputTokens;
                value.totalTokens = row.totalTokens;
                value.cachedTokens = row.cachedTokens;
                value.costUsd = row.costUsd;
                return value;
            })
            .toList();
    }
}
