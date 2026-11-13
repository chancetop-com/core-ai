package ai.core.server.trace;

import java.time.ZonedDateTime;

/**
 * One day of a single user's token usage, summed from {@code trace_daily_stats}.
 *
 * @author stephen
 */
public class TraceDailyUsageRow {
    public ZonedDateTime date;
    public Long inputTokens;
    public Long outputTokens;
    public Long totalTokens;
    public Long cachedTokens;
    public Double costUsd;
}
