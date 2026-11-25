package ai.core.server.trace;

/**
 * @author stephen
 */
public class PgSpanTokenTotalsRow {
    public String traceId;
    public Long inputTokens;
    public Long outputTokens;
    public Long cachedTokens;
    public Double costUsd;
}
