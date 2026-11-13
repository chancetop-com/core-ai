package ai.core.server.trace;

import ai.core.server.trace.domain.AnalyticsDailyStats;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Storage and rollup queries for the pre-aggregated analytics day rows, grouped by user, agent, source,
 * model and provider. The rows are derived from traces, so the daily job can always rebuild them.
 *
 * @author stephen
 */
public interface AnalyticsStatsStore {
    void upsert(AnalyticsDailyStats stats);

    /**
     * @param dimension null for the global summary
     */
    List<TraceStatsRow> stats(ZonedDateTime from, ZonedDateTime to, TraceDimension dimension);

    List<TraceTrendRow> trend(ZonedDateTime from, ZonedDateTime to, boolean hourly);

    List<TraceDimensionTrendRow> dimensionTrend(TraceDimension dimension, ZonedDateTime from, ZonedDateTime to,
                                                List<String> keys);
}
