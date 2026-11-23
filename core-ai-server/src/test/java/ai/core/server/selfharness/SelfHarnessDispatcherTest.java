package ai.core.server.selfharness;

import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.service.TraceAccessControl;
import ai.core.server.trace.service.TraceListFilter;
import ai.core.server.trace.service.TraceService;
import ai.core.server.trace.service.TraceVisibility;
import ai.core.server.trace.service.TraceVisibilityResolver;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Self-harness trace tools must honour the same visibility as the trace web API; a caller with no
 * identity falls back to "internal" and therefore sees nothing.
 */
class SelfHarnessDispatcherTest {
    private final TraceService traceService = mock(TraceService.class);
    private final TraceAccessControl traceAccessControl = mock(TraceAccessControl.class);
    private final TraceVisibilityResolver traceVisibilityResolver = mock(TraceVisibilityResolver.class);

    @Test
    void listTracesIsScopedToTheCallersVisibleUsers() {
        when(traceService.list(any(TraceListFilter.class))).thenReturn(List.of());
        when(traceService.count(any(TraceListFilter.class))).thenReturn(0L);

        var result = (Map<?, ?>) dispatcher().dispatch("list_traces", "{}", null);

        var captor = ArgumentCaptor.forClass(TraceListFilter.class);
        verify(traceService).list(captor.capture());
        assertEquals(List.of("internal"), captor.getValue().userIds);
        assertNull(captor.getValue().userId);
        assertEquals(0L, result.get("total"));
    }

    @Test
    void traceOutsideScopeLooksAbsent() {
        var trace = trace("t1", "other");
        when(traceService.getWithPayload("t1")).thenReturn(trace);
        when(traceAccessControl.canRead(eq(trace), eq("internal"), any(TraceVisibility.class))).thenReturn(Boolean.FALSE);

        assertNull(dispatcher().dispatch("get_trace", "{\"trace_id\":\"t1\"}", null));
        assertNull(dispatcher().dispatch("get_trace_spans", "{\"trace_id\":\"t1\"}", null));
        verify(traceService, never()).spans(any());
    }

    @Test
    void traceInsideScopeIsReturned() {
        var trace = trace("t1", "internal");
        when(traceService.getWithPayload("t1")).thenReturn(trace);
        when(traceAccessControl.canRead(eq(trace), eq("internal"), any(TraceVisibility.class))).thenReturn(Boolean.TRUE);

        assertSame(trace, dispatcher().dispatch("get_trace", "{\"trace_id\":\"t1\"}", null));
    }

    @Test
    void sessionSummaryUsesTheVisibleSet() {
        when(traceService.sessionSummary(eq("s1"), anyList())).thenReturn(Map.of("session_id", "s1"));

        dispatcher().dispatch("get_session_trace_summary", "{\"session_id\":\"s1\"}", null);

        verify(traceService).sessionSummary("s1", List.of("internal"));
    }

    private SelfHarnessDispatcher dispatcher() {
        var dispatcher = new SelfHarnessDispatcher();
        dispatcher.traceService = traceService;
        dispatcher.traceAccessControl = traceAccessControl;
        dispatcher.traceVisibilityResolver = traceVisibilityResolver;
        when(traceVisibilityResolver.resolve("internal")).thenReturn(TraceVisibility.of(Set.of("internal")));
        return dispatcher;
    }

    private Trace trace(String traceId, String userId) {
        var trace = new Trace();
        trace.id = traceId;
        trace.traceId = traceId;
        trace.userId = userId;
        return trace;
    }
}
