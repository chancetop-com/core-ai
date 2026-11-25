package ai.core.server.trace.service;

import ai.core.server.trace.SpanStore;
import ai.core.server.trace.TraceStore;
import ai.core.server.trace.TraceUpdate;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author core-ai
 */
class TraceTotalsReconcilerTest {
    @Test
    void writesAbsoluteTotalsFromSpanRows() {
        var spans = mock(SpanStore.class);
        var traces = mock(TraceStore.class);
        when(spans.tokenTotals(any())).thenReturn(List.of(
            new SpanStore.TokenTotals("t1", 11, 7, 2, 0.5)));

        TraceTotalsReconciler.recompute(spans, traces, Set.of("t1"));

        var captor = ArgumentCaptor.forClass(TraceUpdate.class);
        verify(traces).update(captor.capture());
        var update = captor.getValue();
        assertEquals("t1", update.traceId);
        assertEquals(11, update.inputTokens);
        assertEquals(7, update.outputTokens);
        assertEquals(18, update.totalTokens);
        assertEquals(2, update.cachedTokens);
        assertEquals(0.5, update.costUsd);
    }
}
