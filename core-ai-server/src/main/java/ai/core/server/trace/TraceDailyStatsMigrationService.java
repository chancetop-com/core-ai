package ai.core.server.trace;

import ai.core.server.pg.PagedBackfill;
import ai.core.server.trace.domain.TraceDailyStats;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;

/**
 * Copies legacy Mongo daily stats into PostgreSQL. The rows are derived from traces, so a missing row
 * only costs a rebuild; the copy still pages and resumes like the other backfills.
 *
 * @author stephen
 */
public class TraceDailyStatsMigrationService extends PagedBackfill<TraceDailyStats> {
    private static final String MARKER = "trace_daily_stats_v1";
    private static final String LOCK_KEY = "trace_daily_stats_migration";
    private static final int PAGE_SIZE = 500;

    @Inject
    MongoCollection<TraceDailyStats> statsCollection;
    @Inject
    TraceDailyStatsStore statsStore;

    @Override
    protected String name() {
        return "trace-daily-stats-migration";
    }

    @Override
    protected String marker() {
        return MARKER;
    }

    @Override
    protected String lockKey() {
        return LOCK_KEY;
    }

    @Override
    protected List<TraceDailyStats> page(String cursor) {
        var query = new Query();
        query.filter = Filters.gt("_id", cursor);
        query.sort = Sorts.ascending("_id");
        query.limit = PAGE_SIZE;
        return statsCollection.find(query);
    }

    @Override
    protected String key(TraceDailyStats row) {
        return row.id;
    }

    @Override
    protected void write(List<TraceDailyStats> page) {
        for (var row : page) {
            statsStore.upsert(row);
        }
    }

    @Override
    protected void writeOne(TraceDailyStats row) {
        statsStore.upsert(row);
    }
}
