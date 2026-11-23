package ai.core.server.trace;

import java.time.ZonedDateTime;

/**
 * One time bucket of LLM call throughput, read from {@code app.spans} (type {@code LLM}).
 *
 * @author stephen
 */
public class LlmThroughputRow {
    public ZonedDateTime bucketStart;
    public Long calls;
    public Long errors;
    public Long inputTokens;
    public Long outputTokens;
    public Long cachedTokens;
    public Double costUsd;
}
