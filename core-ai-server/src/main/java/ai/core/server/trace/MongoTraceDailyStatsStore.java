package ai.core.server.trace;

import ai.core.server.trace.domain.TraceDailyStats;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import org.bson.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * @author stephen
 */
public class MongoTraceDailyStatsStore implements TraceDailyStatsStore {
    public static MongoTraceDailyStatsStore of(MongoCollection<TraceDailyStats> collection) {
        var store = new MongoTraceDailyStatsStore();
        store.statsCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<TraceDailyStats> statsCollection;

    @Override
    public void upsert(TraceDailyStats stats) {
        if (statsCollection.get(stats.id).isPresent()) {
            statsCollection.replace(stats);
        } else {
            statsCollection.insert(stats);
        }
    }

    @Override
    public long countByDate(ZonedDateTime dayStart) {
        return statsCollection.count(Filters.eq("date", dayStart));
    }

    @Override
    public List<TraceDailyUsageRow> dailyUsage(String userId, ZonedDateTime from, ZonedDateTime to) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(
                Filters.eq("user_id", userId),
                Filters.gte("date", from),
                Filters.lte("date", to))),
            Aggregates.group(new Document("date", "$date"),
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("total_tokens", "$total_tokens"),
                Accumulators.sum("cached_tokens", "$cached_tokens"),
                Accumulators.sum("cost_usd", "$cost_usd")));
        var rows = new ArrayList<TraceDailyUsageRow>();
        for (var row : statsCollection.aggregate(aggregate)) {
            var group = row.get("_id", Document.class);
            var date = group != null ? group.get("date") : null;
            var value = new TraceDailyUsageRow();
            value.date = date instanceof Date d ? ZonedDateTime.ofInstant(d.toInstant(), ZoneOffset.UTC) : null;
            value.inputTokens = longValue(row, "input_tokens");
            value.outputTokens = longValue(row, "output_tokens");
            value.totalTokens = longValue(row, "total_tokens");
            value.cachedTokens = longValue(row, "cached_tokens");
            value.costUsd = doubleValue(row, "cost_usd");
            rows.add(value);
        }
        return rows;
    }

    private long longValue(Document row, String key) {
        var value = row.get(key);
        return value instanceof Number number ? number.longValue() : 0L;
    }

    private double doubleValue(Document row, String key) {
        var value = row.get(key);
        return value instanceof Number number ? number.doubleValue() : 0.0;
    }
}
