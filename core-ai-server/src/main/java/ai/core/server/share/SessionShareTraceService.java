package ai.core.server.share;

import ai.core.api.server.trace.ListSpansResponse;
import ai.core.api.server.trace.SpanView;
import ai.core.api.server.trace.TraceView;
import ai.core.server.trace.SpanStore;
import ai.core.server.trace.service.TraceService;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;

import java.util.Objects;

/**
 * @author Xander
 */
public class SessionShareTraceService {
    private static final int MAX_PUBLIC_SPANS = 1000;

    @Inject
    SessionShareService shareService;
    @Inject
    TraceService traceService;
    @Inject
    SpanStore spanStore;
    @Inject
    SessionShareProjection projection;

    public TraceView trace(String token, String traceId) {
        var share = shareService.requirePublic(token);
        var trace = shareService.requireTrace(share, traceId);
        var hydrated = traceService.getWithPayload(trace.traceId);
        if (hydrated == null || !Objects.equals(hydrated.sessionId, share.sessionId)
            || !Objects.equals(hydrated.userId, share.ownerUserId) || !Objects.equals(hydrated.traceId, traceId)) {
            throw new NotFoundException("shared content is unavailable");
        }
        return projection.trace(hydrated, share);
    }

    public ListSpansResponse spans(String token, String traceId) {
        var share = shareService.requirePublic(token);
        var trace = shareService.requireTrace(share, traceId);
        var spans = spanStore.summariesByTrace(trace.traceId, MAX_PUBLIC_SPANS + 1);
        if (spans.size() > MAX_PUBLIC_SPANS) {
            throw new BadRequestException("trace exceeds the public sharing limit of 1000 spans");
        }
        var response = new ListSpansResponse();
        response.spans = spans.stream()
            .filter(span -> Objects.equals(span.traceId, traceId))
            .map(span -> projection.span(span, share)).toList();
        return response;
    }

    public SpanView span(String token, String traceId, String spanId) {
        var share = shareService.requirePublic(token);
        var trace = shareService.requireTrace(share, traceId);
        var span = traceService.span(trace.traceId, spanId);
        if (span == null || !Objects.equals(span.traceId, traceId) || !Objects.equals(span.spanId, spanId)) {
            throw new NotFoundException("shared content is unavailable");
        }
        return projection.span(span, share);
    }
}
