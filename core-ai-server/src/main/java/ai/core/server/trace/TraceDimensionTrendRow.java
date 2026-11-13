package ai.core.server.trace;

import java.time.ZonedDateTime;

/**
 * One hourly point of a single dimension's realtime trend.
 *
 * @author stephen
 */
public class TraceDimensionTrendRow {
    public String key;
    public ZonedDateTime bucket;
    public Long inputTokens;
    public Long outputTokens;
    public Long cachedTokens;
    public Long callCount;
    public Double costUsd;
}
