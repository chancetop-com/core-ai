package ai.core.server.analytics;

import ai.core.server.trace.TraceDimensionTrendRow;
import ai.core.server.trace.TraceStatsRow;
import ai.core.server.trace.TraceTrendRow;
import org.bson.Document;

import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

/**
 * Maps the daily-stat Documents (still stored in Mongo) onto the same typed rows the trace store
 * returns, so the analytics builders only deal with one shape.
 *
 * @author stephen
 */
public final class AnalyticsRowMapper {
    public static List<TraceStatsRow> statsRows(List<Document> rows) {
        var converted = new ArrayList<TraceStatsRow>(rows.size());
        for (var row : rows) {
            var value = new TraceStatsRow();
            value.key = row.getString("_id");
            value.agentName = row.getString("agent_name");
            value.providerName = row.getString("provider_name");
            value.inputTokens = longValue(row, "input_tokens");
            value.outputTokens = longValue(row, "output_tokens");
            value.totalTokens = longValue(row, "total_tokens");
            value.cachedTokens = longValue(row, "cached_tokens");
            value.callCount = longValue(row, "call_count");
            value.costUsd = doubleValue(row, "cost_usd");
            value.avgTotalTokens = doubleValue(row, "avg_total_tokens");
            value.avgCostUsd = doubleValue(row, "avg_cost_usd");
            value.maxTotalTokens = longValue(row, "max_total_tokens");
            value.maxCostUsd = doubleValue(row, "max_cost_usd");
            value.p90TotalTokens = doubleValue(row, "p90_total_tokens");
            converted.add(value);
        }
        return converted;
    }

    public static List<TraceTrendRow> trendRows(List<Document> rows) {
        var converted = new ArrayList<TraceTrendRow>(rows.size());
        for (var row : rows) {
            var value = new TraceTrendRow();
            value.bucket = bucket(row.get("_id"));
            value.inputTokens = longValue(row, "input_tokens");
            value.outputTokens = longValue(row, "output_tokens");
            value.cachedTokens = longValue(row, "cached_tokens");
            value.callCount = longValue(row, "call_count");
            value.costUsd = doubleValue(row, "cost_usd");
            converted.add(value);
        }
        return converted;
    }

    public static List<TraceDimensionTrendRow> dimensionTrendRows(List<Document> rows) {
        var converted = new ArrayList<TraceDimensionTrendRow>(rows.size());
        for (var row : rows) {
            var id = row.get("_id", Document.class);
            var value = new TraceDimensionTrendRow();
            value.key = id != null ? id.getString("dim") : null;
            value.bucket = bucket(id != null ? id.get("date") : null);
            value.inputTokens = longValue(row, "input_tokens");
            value.outputTokens = longValue(row, "output_tokens");
            value.cachedTokens = longValue(row, "cached_tokens");
            value.callCount = longValue(row, "call_count");
            value.costUsd = doubleValue(row, "cost_usd");
            converted.add(value);
        }
        return converted;
    }

    private static ZonedDateTime bucket(Object value) {
        if (value instanceof Date date) return ZonedDateTime.ofInstant(date.toInstant(), ZoneOffset.UTC);
        return null;
    }

    private static long longValue(Document row, String key) {
        Number value = row.get(key, Number.class);
        return value != null ? value.longValue() : 0L;
    }

    private static double doubleValue(Document row, String key) {
        Number value = row.get(key, Number.class);
        return value != null ? value.doubleValue() : 0.0;
    }

    private AnalyticsRowMapper() {
    }
}
