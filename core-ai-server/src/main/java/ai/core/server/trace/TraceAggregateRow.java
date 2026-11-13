package ai.core.server.trace;

/**
 * One row of a {@link TraceAggregation}: the group key, the optional first value, and the sums.
 * Null fields mean the aggregation did not ask for that metric.
 *
 * @author stephen
 */
public class TraceAggregateRow {
    public String group;
    public String firstValue;
    public Long inputTokens;
    public Long outputTokens;
    public Long totalTokens;
    public Long cachedTokens;
    public Long count;
    public Double costUsd;
}
