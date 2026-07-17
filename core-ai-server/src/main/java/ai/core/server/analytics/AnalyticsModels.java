package ai.core.server.analytics;

import java.util.List;

/**
 * Response DTOs for admin analytics API.
 */
final class AnalyticsModels {

    private AnalyticsModels() {
    }

    record GlobalSummary(
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

    record TrendPoint(
        String timestamp,
        long inputTokens,
        long outputTokens,
        long cachedTokens,
        double costUsd,
        long callCount
    ) {
    }

    record DimensionItem(
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

    record DimensionAnalytics(
        List<DimensionItem> items,
        GlobalSummary totals
    ) {
    }
}
