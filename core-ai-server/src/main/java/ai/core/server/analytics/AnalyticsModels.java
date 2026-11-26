package ai.core.server.analytics;

import java.util.List;

/**
 * Response DTOs for admin analytics API.
 */
public final class AnalyticsModels {

    private AnalyticsModels() {
    }

    public record GlobalSummary(
        long totalInputTokens,
        long totalOutputTokens,
        long totalTokens,
        long totalCachedTokens,
        double totalCostUsd,
        long totalCalls,
        double avgTokensPerCall,
        double avgCostPerCall,
        long maxTokensPerCall,
        double maxCostPerCall,
        double p90TokensPerCall,
        Long prevTotalTokens,
        Double prevTotalCostUsd
    ) {
    }

    public record TrendPoint(
        String timestamp,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        double costUsd,
        long callCount
    ) {
    }

    public record DimensionTrendPoint(
        String key,
        String timestamp,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        double costUsd,
        long callCount
    ) {
    }

    public record DimensionItem(
        String key,
        String label,
        long inputTokens,
        long outputTokens,
        long totalTokens,
        long cachedTokens,
        double costUsd,
        long callCount,
        double avgInputTokens,
        double avgOutputTokens,
        double avgTotalTokens,
        double avgCostUsd,
        long maxTotalTokens,
        double maxCostUsd,
        double p90TotalTokens,
        double tokenShare,
        double costShare
    ) {
    }

    public record DimensionAnalytics(
        List<DimensionItem> items,
        GlobalSummary totals
    ) {
    }

    public record LlmThroughputPoint(
        String bucketStart,
        long calls,
        long errors,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        double costUsd
    ) {
    }

    public record LlmModelMetrics(
        String model,
        long calls,
        long errors,
        long callsWithTtft,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        double costUsd,
        Long avgTtftMs,
        Long p50TtftMs,
        Long p95TtftMs,
        Long avgDurationMs,
        Long p50DurationMs,
        Long p95DurationMs,
        Double avgOutputTokensPerSecond,
        Double p50OutputTokensPerSecond,
        Double p95OutputTokensPerSecond
    ) {
    }

    public record LlmMetrics(
        int windowMinutes,
        int bucketMinutes,
        List<LlmThroughputPoint> points,
        List<LlmModelMetrics> models
    ) {
    }
}
