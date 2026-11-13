package ai.core.server.trace;

import ai.core.server.trace.domain.Span;

import java.time.ZonedDateTime;
import java.util.List;

/**
 * Span storage. Span payloads are large and write-heavy, so callers pick the summary queries
 * when the timeline does not need input/output.
 *
 * @author stephen
 */
public interface SpanStore {
    void insert(Span span);

    Span find(String traceId, String spanId);

    boolean existsToolCall(String traceId, String toolCallId);

    List<Span> summariesByTrace(String traceId, int limit);

    List<Span> summariesByTraces(List<String> traceIds, int limit);

    List<Span> llmSpansByTraces(List<String> traceIds, int limit);

    List<Span> generations(int offset, int limit, String model, List<String> userIds, List<String> traceIds);

    List<Span> before(ZonedDateTime cutoff, int offset, int limit);

    List<Span> pageByTrace(String traceId, int offset, int limit);

    long countBefore(ZonedDateTime cutoff);

    long deleteByTraceIds(List<String> traceIds);

    long deleteByIds(List<String> ids);
}
