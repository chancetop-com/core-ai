package ai.core.server.apiuser;

import ai.core.api.server.apiuser.response.DailyUsageView;
import ai.core.api.server.apiuser.response.UsageView;
import ai.core.server.trace.domain.Trace;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.bson.Document;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Token usage aggregation over traces for a single API user.
 *
 * @author stephen
 */
public class ApiUserUsageService {
    private static final int MAX_RANGE_DAYS = 90;
    private static final String DATE_FORMAT = "%Y-%m-%d";

    @Inject
    MongoCollection<Trace> traceCollection;

    public UsageView usage(String userId, String from, String to) {
        var start = parse(from);
        var end = parse(to);
        validateRange(start, end);

        var filter = Filters.and(
                Filters.eq("user_id", userId),
                Filters.gte("created_at", start),
                Filters.lte("created_at", end));

        // group by UTC day (created_at date string); totals are then aggregated in memory from daily rows
        var aggregate = new Aggregate<ApiUserDailyUsageRow>();
        aggregate.resultClass = ApiUserDailyUsageRow.class;
        aggregate.pipeline = List.of(
                Aggregates.match(filter),
                Aggregates.project(Projections.fields(
                        Projections.include("total_tokens", "input_tokens", "output_tokens", "cached_tokens", "cost_usd"),
                        Projections.computed("day", new Document("$dateToString",
                                new Document("format", DATE_FORMAT).append("date", "$created_at"))))),
                Aggregates.group("$day",
                        Accumulators.sum("totalTokens", "$total_tokens"),
                        Accumulators.sum("inputTokens", "$input_tokens"),
                        Accumulators.sum("outputTokens", "$output_tokens"),
                        Accumulators.sum("cachedTokens", "$cached_tokens"),
                        Accumulators.sum("costUsd", "$cost_usd"),
                        Accumulators.sum("callCount", 1)));

        return toView(traceCollection.aggregate(aggregate));
    }

    private void validateRange(ZonedDateTime start, ZonedDateTime end) {
        if (start.isAfter(end)) throw new BadRequestException("from must be before to");
        if (ChronoUnit.DAYS.between(start, end) > MAX_RANGE_DAYS) {
            throw new BadRequestException("range exceeds " + MAX_RANGE_DAYS + " days");
        }
    }

    private UsageView toView(List<ApiUserDailyUsageRow> dailyRows) {
        var response = new UsageView();
        long totalTokens = 0;
        long inputTokens = 0;
        long outputTokens = 0;
        long cachedTokens = 0;
        long callCount = 0;
        double costUsd = 0;
        var byDay = new ArrayList<DailyUsageView>(dailyRows.size());
        for (var row : dailyRows) {
            totalTokens += safeLong(row.totalTokens);
            inputTokens += safeLong(row.inputTokens);
            outputTokens += safeLong(row.outputTokens);
            cachedTokens += safeLong(row.cachedTokens);
            costUsd += safeDouble(row.costUsd);
            callCount += safeLong(row.callCount);

            var daily = new DailyUsageView();
            daily.date = row.day;
            daily.totalTokens = safeLong(row.totalTokens);
            daily.inputTokens = safeLong(row.inputTokens);
            daily.outputTokens = safeLong(row.outputTokens);
            daily.cachedTokens = safeLong(row.cachedTokens);
            daily.costUsd = safeDouble(row.costUsd);
            daily.callCount = safeLong(row.callCount);
            byDay.add(daily);
        }
        response.totalTokens = totalTokens;
        response.inputTokens = inputTokens;
        response.outputTokens = outputTokens;
        response.cachedTokens = cachedTokens;
        response.costUsd = costUsd;
        response.callCount = callCount;
        response.byDay = byDay;
        return response;
    }

    private ZonedDateTime parse(String value) {
        try {
            return ZonedDateTime.parse(value);
        } catch (Exception e) {
            throw new BadRequestException("invalid date time: " + value, "INVALID_DATE", e);
        }
    }

    private long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private double safeDouble(Double value) {
        return value != null ? value : 0.0;
    }
}
