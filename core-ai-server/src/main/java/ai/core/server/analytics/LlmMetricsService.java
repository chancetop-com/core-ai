package ai.core.server.analytics;

import ai.core.server.trace.LlmModelMetricsRow;
import ai.core.server.trace.LlmThroughputRow;
import ai.core.server.trace.SpanStore;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * LLM operations metrics for the admin dashboard: call/token throughput per time bucket (RPM/TPM) and
 * per-model latency, TTFT and error metrics.
 * <p>
 * The source is the raw LLM spans, not the daily rollups: the rollups are day-granular and carry no
 * latency, and this view is about what the fleet is doing minute to minute. Spans are removed by the
 * trace retention window, so the window cannot extend past it.
 *
 * @author stephen
 */
public class LlmMetricsService {
    public static final int MAX_WINDOW_MINUTES = 7 * 24 * 60;
    // protects the dashboard from a minute-resolution query over a week (and the payload that follows)
    private static final int MAX_POINTS = 2000;

    private static long value(Long value) {
        return value != null ? value : 0L;
    }

    private static double value(Double value) {
        return value != null ? value : 0.0;
    }

    private static Long round(Double value) {
        return value != null ? Math.round(value) : null;
    }

    @Inject
    SpanStore spanStore;

    public AnalyticsModels.LlmMetrics metrics(Integer minutes, Integer bucketMinutes) {
        int window = minutes == null ? 60 : minutes;
        if (window < 1 || window > MAX_WINDOW_MINUTES) {
            throw new BadRequestException("minutes must be between 1 and " + MAX_WINDOW_MINUTES);
        }
        int bucket = bucketMinutes == null ? 1 : bucketMinutes;
        if (bucket < 1) throw new BadRequestException("bucketMinutes must be at least 1");
        bucket = Math.max(bucket, (window + MAX_POINTS - 1) / MAX_POINTS);
        if (bucket > window) bucket = window;

        var now = ZonedDateTime.now();
        var from = now.minusMinutes(window);
        return new AnalyticsModels.LlmMetrics(window, bucket,
            toPoints(spanStore.llmThroughput(from, now, bucket)),
            toModels(spanStore.llmModelMetrics(from, now)));
    }

    private List<AnalyticsModels.LlmThroughputPoint> toPoints(List<LlmThroughputRow> rows) {
        var points = new ArrayList<AnalyticsModels.LlmThroughputPoint>(rows.size());
        for (var row : rows) {
            points.add(new AnalyticsModels.LlmThroughputPoint(
                row.bucketStart != null ? row.bucketStart.toInstant().toString() : "",
                value(row.calls), value(row.errors),
                value(row.inputTokens), value(row.outputTokens), value(row.cachedTokens),
                value(row.costUsd)));
        }
        return points;
    }

    private List<AnalyticsModels.LlmModelMetrics> toModels(List<LlmModelMetricsRow> rows) {
        var models = new ArrayList<AnalyticsModels.LlmModelMetrics>(rows.size());
        for (var row : rows) {
            models.add(new AnalyticsModels.LlmModelMetrics(
                row.model, value(row.calls), value(row.errors), value(row.callsWithTtft),
                value(row.inputTokens), value(row.outputTokens), value(row.cachedTokens), value(row.costUsd),
                round(row.avgTtftMs), round(row.p50TtftMs), round(row.p95TtftMs),
                round(row.avgDurationMs), round(row.p50DurationMs), round(row.p95DurationMs),
                row.avgOutputTokensPerSecond));
        }
        return models;
    }
}
