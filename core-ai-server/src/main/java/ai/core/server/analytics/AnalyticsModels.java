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
}
