package ai.core.server.trace;

import ai.core.server.trace.domain.Span;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;

/**
 * Span storage. Span payloads are large and write-heavy, so callers pick the summary queries
 * when the timeline does not need input/output.
 *
 * @author stephen
 */
public interface SpanStore {
    void insert(Span span);

    /**
     * Inserts a span unless its (trace_id, span_id) already exists.
     *
     * @return true when this call inserted the row; false when it was a replay
     */
    boolean insertIfAbsent(Span span);

    /**
     * Span ids from the supplied list that are already persisted, used to skip compaction and
     * body offloading on replayed batches before {@link #insertIfAbsent(Span)} acts as the race gate.
     */
    default Set<String> existingSpanIds(List<String> spanIds) {
        return Set.of();
    }

    void bulkInsert(List<Span> spans);

    /** Summed token/cost columns for the supplied traces, used to repair counters after retries. */
    List<TokenTotals> tokenTotals(List<String> traceIds);

    Span find(String traceId, String spanId);

    boolean existsToolCall(String traceId, String toolCallId);

    List<Span> summariesByTrace(String traceId, int limit);

    List<Span> summariesByTraces(List<String> traceIds, int limit);

    List<Span> llmSpansByTraces(List<String> traceIds, int limit);

    List<Span> generations(int offset, int limit, String model, List<String> userIds, List<String> traceIds);

    /** LLM call counts and token sums per time bucket, for the dashboard's RPM/TPM curves. */
    List<LlmThroughputRow> llmThroughput(ZonedDateTime from, ZonedDateTime to, int bucketMinutes);

    /** Per-model latency/TTFT/error metrics over the window, for the dashboard's model table. */
    List<LlmModelMetricsRow> llmModelMetrics(ZonedDateTime from, ZonedDateTime to);

    List<Span> before(ZonedDateTime cutoff, int offset, int limit);

    List<Span> pageByTrace(String traceId, int offset, int limit);

    long countBefore(ZonedDateTime cutoff);

    long deleteByTraceIds(List<String> traceIds);

    long deleteByIds(List<String> ids);

    /**
     * Raw input/output values of the supplied traces that carry an externalized body reference.
     * Collected before the rows are deleted so the archive can remove the backing objects.
     */
    default List<String> findBodyRefs(List<String> traceIds) {
        return List.of();
    }

    record TokenTotals(String traceId, long inputTokens, long outputTokens, long cachedTokens, double costUsd) {
    }
}
