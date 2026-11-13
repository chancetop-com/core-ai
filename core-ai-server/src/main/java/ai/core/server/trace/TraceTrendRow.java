package ai.core.server.trace;

import java.time.ZonedDateTime;

/**
 * One hourly point of the realtime token/cost trend.
 *
 * @author stephen
 */
public class TraceTrendRow {
    public ZonedDateTime bucket;
    public Long inputTokens;
    public Long outputTokens;
    public Long cachedTokens;
    public Long callCount;
    public Double costUsd;
}
