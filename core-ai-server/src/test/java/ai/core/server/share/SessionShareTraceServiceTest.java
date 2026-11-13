package ai.core.server.share;

import ai.core.server.domain.SessionShare;
import ai.core.server.trace.SpanStore;
import ai.core.server.trace.domain.Span;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceService;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SessionShareTraceServiceTest {
    private final SessionShareTraceService service = new SessionShareTraceService();
    private final SessionShareService shares = mock(SessionShareService.class);
    private final TraceService traces = mock(TraceService.class);
    private final SpanStore spans = mock(SpanStore.class);
    private final SessionShareProjection projection = mock(SessionShareProjection.class);
    private final SessionShare share = new SessionShare();
    private final Trace trace = new Trace();

    @BeforeEach
    void setUp() {
        service.shareService = shares;
        service.traceService = traces;
        service.spanStore = spans;
        service.projection = projection;
        share.sessionId = "session-1";
        share.ownerUserId = "owner-1";
        trace.traceId = "trace-1";
        trace.sessionId = share.sessionId;
        trace.userId = share.ownerUserId;
        when(shares.requirePublic("token")).thenReturn(share);
        when(shares.requireTrace(share, "trace-1")).thenReturn(trace);
    }

    @Test
    void verifiesShareBeforeAnyTracePayloadLookup() {
        when(shares.requirePublic("revoked")).thenThrow(new NotFoundException("unavailable"));

        assertThrows(NotFoundException.class, () -> service.trace("revoked", "trace-1"));

        verify(traces, never()).getWithPayload(any());
    }

    @Test
    void refusesSpanFromAnotherTraceOrMismatchedSpanId() {
        var span = new Span();
        span.spanId = "span-1";
        span.traceId = "trace-other";
        when(traces.span("trace-1", "span-1")).thenReturn(span);
        assertThrows(NotFoundException.class, () -> service.span("token", "trace-1", "span-1"));
        span.traceId = "trace-1";
        span.spanId = "span-other";
        assertThrows(NotFoundException.class, () -> service.span("token", "trace-1", "span-1"));
        verify(projection, never()).span(any(), any());
    }

    @Test
    void filtersMismatchedTraceFromSpanSummaryResponse() {
        var otherSpan = new Span();
        otherSpan.traceId = "trace-other";
        when(spans.summariesByTrace("trace-1", 1001)).thenReturn(List.of(otherSpan));

        assertEquals(List.of(), service.spans("token", "trace-1").spans);
        verify(projection, never()).span(any(), any());
    }

    @Test
    void boundsSpanQueryAndRejectsOverflowRatherThanReturningPartialTree() {
        when(spans.summariesByTrace("trace-1", 1001)).thenReturn(Collections.nCopies(1001, new Span()));

        assertThrows(BadRequestException.class, () -> service.spans("token", "trace-1"));
        verify(spans).summariesByTrace("trace-1", 1001);
        verify(projection, never()).span(any(), any());
    }

    @Test
    void rechecksAttributionOnHydratedTraceBeforeProjection() {
        var changed = new Trace();
        changed.traceId = "trace-1";
        changed.sessionId = "another-session";
        changed.userId = "owner-1";
        when(traces.getWithPayload("trace-1")).thenReturn(changed);

        assertThrows(NotFoundException.class, () -> service.trace("token", "trace-1"));
        verify(projection, never()).trace(any(), any());
    }
}
