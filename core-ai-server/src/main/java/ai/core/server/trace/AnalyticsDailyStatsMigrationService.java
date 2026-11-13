package ai.core.server.trace;

import ai.core.server.pg.PagedBackfill;
import ai.core.server.trace.domain.AnalyticsDailyStats;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.List;

/**
 * @author stephen
 */
public class AnalyticsDailyStatsMigrationService extends PagedBackfill<AnalyticsDailyStats> {
    private static final String MARKER = "analytics_daily_stats_v1";
    private static final String LOCK_KEY = "analytics_daily_stats_migration";
    private static final int PAGE_SIZE = 500;

    @Inject
    MongoCollection<AnalyticsDailyStats> statsCollection;
    @Inject
    AnalyticsStatsStore statsStore;

    @Override
    protected String name() {
        return "analytics-daily-stats-migration";
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
    protected List<AnalyticsDailyStats> page(String cursor) {
        var query = new Query();
        query.filter = Filters.gt("_id", cursor);
        query.sort = Sorts.ascending("_id");
        query.limit = PAGE_SIZE;
        return statsCollection.find(query);
    }

    @Override
    protected String key(AnalyticsDailyStats row) {
        return row.id;
    }

    @Override
    protected void write(List<AnalyticsDailyStats> page) {
        for (var row : page) {
            statsStore.upsert(row);
        }
    }

    @Override
    protected void writeOne(AnalyticsDailyStats row) {
        statsStore.upsert(row);
    }
}
