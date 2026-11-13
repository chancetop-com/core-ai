package ai.core.server.trace;

import ai.core.server.analytics.AnalyticsRowMapper;
import ai.core.server.trace.domain.AnalyticsDailyStats;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.BsonField;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class MongoAnalyticsStatsStore implements AnalyticsStatsStore {
    public static MongoAnalyticsStatsStore of(MongoCollection<AnalyticsDailyStats> collection) {
        var store = new MongoAnalyticsStatsStore();
        store.statsCollection = collection;
        return store;
    }

    @Inject
    MongoCollection<AnalyticsDailyStats> statsCollection;

    @Override
    public void upsert(AnalyticsDailyStats stats) {
        if (statsCollection.get(stats.id).isPresent()) {
            statsCollection.replace(stats);
        } else {
            statsCollection.insert(stats);
        }
    }

    @Override
    public List<TraceStatsRow> stats(ZonedDateTime from, ZonedDateTime to, TraceDimension dimension) {
        var pipeline = new ArrayList<Bson>();
        pipeline.add(match(from, to));
        pipeline.add(Aggregates.group(dimension != null ? "$" + dimension.field : null,
            Accumulators.sum("input_tokens", "$input_tokens"),
            Accumulators.sum("output_tokens", "$output_tokens"),
            Accumulators.sum("total_tokens", "$total_tokens"),
            Accumulators.sum("cached_tokens", "$cached_tokens"),
            Accumulators.sum("cost_usd", "$cost_usd"),
            Accumulators.sum("call_count", "$call_count"),
            Accumulators.avg("avg_total_tokens", "$avg_total_tokens"),
            Accumulators.avg("avg_cost_usd", "$avg_cost_usd"),
            Accumulators.max("max_total_tokens", "$max_total_tokens"),
            Accumulators.max("max_cost_usd", "$max_cost_usd"),
            Accumulators.max("p90_total_tokens", "$p90_total_tokens"),
            Accumulators.first("agent_name", "$agent_name"),
            Accumulators.first("provider_name", "$provider_name")));
        pipeline.add(Aggregates.sort(Sorts.descending("total_tokens")));
        return AnalyticsRowMapper.statsRows(aggregate(pipeline));
    }

    @Override
    public List<TraceTrendRow> trend(ZonedDateTime from, ZonedDateTime to, boolean hourly) {
        var pipeline = new ArrayList<Bson>();
        pipeline.add(match(from, to));
        pipeline.add(Aggregates.group(hourly
                ? new Document("$dateTrunc", new Document("date", "$date").append("unit", "hour"))
                : new Document("date", "$date"),
            trendFields()));
        pipeline.add(Aggregates.sort(Sorts.ascending("_id")));
        return AnalyticsRowMapper.trendRows(aggregate(pipeline));
    }

    @Override
    public List<TraceDimensionTrendRow> dimensionTrend(TraceDimension dimension, ZonedDateTime from,
                                                       ZonedDateTime to, List<String> keys) {
        if (keys == null || keys.isEmpty()) return List.of();
        var pipeline = new ArrayList<Bson>();
        pipeline.add(Aggregates.match(Filters.and(
            Filters.gte("date", from),
            Filters.lt("date", to),
            Filters.in(dimension.field, keys))));
        pipeline.add(Aggregates.group(new Document("dim", "$" + dimension.field).append("date", "$date"),
            trendFields()));
        pipeline.add(Aggregates.sort(Sorts.ascending("_id.date")));
        return AnalyticsRowMapper.dimensionTrendRows(aggregate(pipeline));
    }

    private List<BsonField> trendFields() {
        return List.of(
            Accumulators.sum("input_tokens", "$input_tokens"),
            Accumulators.sum("output_tokens", "$output_tokens"),
            Accumulators.sum("cached_tokens", "$cached_tokens"),
            Accumulators.sum("cost_usd", "$cost_usd"),
            Accumulators.sum("call_count", "$call_count"));
    }

    private Bson match(ZonedDateTime from, ZonedDateTime to) {
        return Aggregates.match(Filters.and(Filters.gte("date", from), Filters.lt("date", to)));
    }

    private List<Document> aggregate(List<Bson> pipeline) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = pipeline;
        return statsCollection.aggregate(aggregate);
    }
}
