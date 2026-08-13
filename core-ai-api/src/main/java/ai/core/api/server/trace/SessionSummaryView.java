package ai.core.api.server.trace;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class SessionSummaryView {
    @Property(name = "session_id")
    public String sessionId;

    @Property(name = "trace_count")
    public Long traceCount;

    @Property(name = "total_tokens")
    public Long totalTokens;

    @Property(name = "total_cached_tokens")
    public Long totalCachedTokens;

    @Property(name = "total_cost_usd")
    public Double totalCostUsd;

    @Property(name = "total_duration_ms")
    public Long totalDurationMs;

    @Property(name = "error_count")
    public Long errorCount;

    @Property(name = "user_id")
    public String userId;

    @Property(name = "account")
    public TraceAccountView account;

    @Property(name = "last_trace_at")
    public String lastTraceAt;

    @Property(name = "first_trace_at")
    public String firstTraceAt;

    @Property(name = "first_request")
    public String firstRequest;
}
