package ai.core.server.trace;

import ai.core.server.trace.domain.TraceDailyStats;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Storage for the pre-aggregated per user/agent daily stats. The rows are derived from traces, so the
 * daily maintenance job can always rebuild them.
 *
 * @author stephen
 */
public interface TraceDailyStatsStore {
    void upsert(TraceDailyStats stats);

    long countByDate(ZonedDateTime dayStart);

    List<TraceDailyUsageRow> dailyUsage(String userId, ZonedDateTime from, ZonedDateTime to);
}
