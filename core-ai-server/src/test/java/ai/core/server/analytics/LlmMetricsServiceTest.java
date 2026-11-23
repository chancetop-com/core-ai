package ai.core.server.analytics;

import ai.core.server.trace.LlmModelMetricsRow;
import ai.core.server.trace.LlmThroughputRow;
import ai.core.server.trace.SpanStore;
import core.framework.web.exception.BadRequestException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class LlmMetricsServiceTest {
    private SpanStore spanStore;
    private LlmMetricsService service;

    @BeforeEach
    void setUp() {
        spanStore = mock(SpanStore.class);
        service = new LlmMetricsService();
        service.spanStore = spanStore;
        when(spanStore.llmThroughput(any(), any(), anyInt())).thenReturn(List.of());
        when(spanStore.llmModelMetrics(any(), any())).thenReturn(List.of());
    }

    @Test
    void defaultsToOneMinuteBucketsOverTheLastHour() {
        var metrics = service.metrics(null, null);

        assertEquals(60, metrics.windowMinutes());
        assertEquals(1, metrics.bucketMinutes());
    }

    @Test
    void widensTheBucketSoLongWindowsStayWithinThePointBudget() {
        var metrics = service.metrics(7 * 24 * 60, 1);

        assertEquals(7 * 24 * 60, metrics.windowMinutes());
        assertEquals(6, metrics.bucketMinutes());
        verify(spanStore).llmThroughput(any(), any(), anyInt());
    }

    @Test
    void rejectsWindowsOutsideTheSupportedRange() {
        assertThrows(BadRequestException.class, () -> service.metrics(0, 1));
        assertThrows(BadRequestException.class, () -> service.metrics(LlmMetricsService.MAX_WINDOW_MINUTES + 1, 1));
        assertThrows(BadRequestException.class, () -> service.metrics(60, 0));
    }

    @Test
    void mapsBucketRowsAndFillsMissingCounters() {
        var row = new LlmThroughputRow();
        row.bucketStart = ZonedDateTime.parse("2026-11-23T10:05:00Z");
        row.calls = 12L;
        row.errors = null;
        row.inputTokens = 1000L;
        row.outputTokens = null;
        row.cachedTokens = null;
        row.costUsd = null;
        when(spanStore.llmThroughput(any(), any(), anyInt())).thenReturn(List.of(row));

        var metrics = service.metrics(60, 1);

        var point = metrics.points().getFirst();
        assertEquals("2026-11-23T10:05:00Z", point.bucketStart());
        assertEquals(12L, point.calls());
        assertEquals(0L, point.errors());
        assertEquals(1000L, point.inputTokens());
        assertEquals(0L, point.outputTokens());
        assertEquals(0.0, point.costUsd());
    }

    @Test
    void mapsModelRowsRoundingLatencyToWholeMilliseconds() {
        var row = new LlmModelMetricsRow();
        row.model = "gpt-5.6-terra";
        row.calls = 10L;
        row.errors = 1L;
        row.callsWithTtft = 8L;
        row.inputTokens = 100L;
        row.outputTokens = 200L;
        row.cachedTokens = 0L;
        row.costUsd = 0.5;
        row.avgTtftMs = 123.6;
        row.p50TtftMs = 100.4;
        row.p95TtftMs = 900.5;
        row.avgDurationMs = 2345.6;
        row.p50DurationMs = 2000.2;
        row.p95DurationMs = null;
        row.avgOutputTokensPerSecond = 42.5;
        when(spanStore.llmModelMetrics(any(), any())).thenReturn(List.of(row));

        var metrics = service.metrics(60, 1);

        var model = metrics.models().getFirst();
        assertEquals("gpt-5.6-terra", model.model());
        assertEquals(124L, model.avgTtftMs());
        assertEquals(100L, model.p50TtftMs());
        assertEquals(901L, model.p95TtftMs());
        assertEquals(2346L, model.avgDurationMs());
        assertNull(model.p95DurationMs());
        assertEquals(42.5, model.avgOutputTokensPerSecond());
        assertEquals(8L, model.callsWithTtft());
    }

    @Test
    void queriesTheWindowEndingNow() {
        service.metrics(120, 5);

        var from = org.mockito.ArgumentCaptor.forClass(ZonedDateTime.class);
        var to = org.mockito.ArgumentCaptor.forClass(ZonedDateTime.class);
        verify(spanStore).llmModelMetrics(from.capture(), to.capture());
        var windowMinutes = java.time.Duration.between(from.getValue(), to.getValue()).toMinutes();
        assertTrue(windowMinutes >= 119 && windowMinutes <= 120, "unexpected window: " + windowMinutes);
    }
}
