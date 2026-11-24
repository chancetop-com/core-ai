package ai.core.server.trace.spi;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.core.server.trace.service.OTLPIngestService;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class LocalSpanProcessorTest {
    private static final String TRACE_ID = "4e63b9c150f77777b4cd77e9149048d6";

    private final OTLPIngestService ingestService = mock(OTLPIngestService.class);

    @BeforeEach
    void registerIngestService() {
        LocalSpanProcessorRegistry.register(ingestService);
    }

    @AfterEach
    void clearIngestService() {
        LocalSpanProcessorRegistry.clear();
        LocalSpanProcessor.configureBudget(0, 0);
    }

    @Test
    void createsTraceWhenRootSpanWithIdentityStarts() {
        var processor = new LocalSpanProcessor("core-ai-server", "1", "uat");
        assertTrue(processor.isStartRequired(), "the SDK only reports span starts when the processor asks for them");
        var span = span("agent.run", Attributes.builder()
            .put("session.id", "run:2de32893")
            .put("user.id", "api:c1791592")
            .put("gen_ai.agent.name", "SEO - GBP Content Agent")
            .build(), 5_000_000_000L);

        processor.onStart(Context.root(), span);

        verify(ingestService, timeout(2_000)).traceStarted(
            eq(TRACE_ID), eq("agent.run"), eq(5_000L),
            argThat(attrs -> "run:2de32893".equals(attrs.get("session.id")) && "api:c1791592".equals(attrs.get("user.id"))),
            argThat(resource -> "core-ai-server".equals(resource.get("service.name")) && "uat".equals(resource.get("deployment.environment"))));
    }

    @Test
    void skipsChildSpanEvenWithIdentity() {
        var processor = new LocalSpanProcessor("core-ai-server", "1", "uat");
        var span = span("chat", Attributes.builder().put("user.id", "u-1").build(), 1_000L);
        var parentContext = Context.root().with(Span.wrap(spanContext()));
        assertTrue(Span.fromContext(parentContext).getSpanContext().isValid());

        processor.onStart(parentContext, span);

        verifyNoInteractions(ingestService);
    }

    @Test
    void skipsRootSpanWithoutIdentity() {
        var processor = new LocalSpanProcessor("core-ai-server", "1", "uat");
        var span = span("chat", Attributes.builder().put("gen_ai.request.model", "gpt-5.6-terra").build(), 1_000L);

        processor.onStart(Context.root(), span);

        verifyNoInteractions(ingestService);
    }

    @Test
    void stopsCreatingTracesAfterShutdown() {
        var processor = new LocalSpanProcessor("core-ai-server", "1", "uat");
        processor.shutdown();
        var span = span("agent.run", Attributes.builder().put("user.id", "u-1").build(), 1_000L);

        processor.onStart(Context.root(), span);

        verifyNoInteractions(ingestService);
    }

    @Test
    void dropsASpanLargerThanThePerSpanCeiling() {
        LocalSpanProcessor.configureBudget(0, 1024);
        var processor = new LocalSpanProcessor("core-ai-server", "1", "uat");
        var span = span("agent.run", Attributes.builder().put("langfuse.observation.input", "x".repeat(4096)).build(), 1_000L);
        long oversizeBefore = LocalSpanProcessor.droppedOversizeSpans();

        processor.onEnd(span);

        assertEquals(oversizeBefore + 1, LocalSpanProcessor.droppedOversizeSpans());
        assertEquals(0, LocalSpanProcessor.queuedBytes());
        verifyNoInteractions(ingestService);
    }

    @Test
    void dropsSpansWhenTheByteBudgetIsExhausted() {
        LocalSpanProcessor.configureBudget(1, 0);
        var processor = new LocalSpanProcessor("core-ai-server", "1", "uat");
        var span = span("agent.run", Attributes.builder().put("k", "v").build(), 1_000L);
        long droppedBefore = LocalSpanProcessor.droppedSpans();
        long droppedBytesBefore = LocalSpanProcessor.droppedSpanBytes();

        processor.onEnd(span);

        assertEquals(droppedBefore + 1, LocalSpanProcessor.droppedSpans());
        assertTrue(LocalSpanProcessor.droppedSpanBytes() > droppedBytesBefore);
        assertEquals(0, LocalSpanProcessor.queuedBytes());
        verifyNoInteractions(ingestService);
    }

    @Test
    void releasesQueuedBytesAfterIngest() throws InterruptedException {
        var processor = new LocalSpanProcessor("core-ai-server", "1", "uat");
        var span = span("agent.run", Attributes.builder().put("k", "v").build(), 1_000L);

        processor.onEnd(span);

        verify(ingestService, timeout(2_000)).ingest(any());
        var deadline = System.currentTimeMillis() + 2_000;
        while (LocalSpanProcessor.queuedBytes() > 0 && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }
        assertEquals(0, LocalSpanProcessor.queuedBytes());
    }

    private ReadWriteSpan span(String name, Attributes attributes, long startNanos) {
        var span = mock(ReadWriteSpan.class);
        var spanData = mock(SpanData.class);
        when(span.getSpanContext()).thenReturn(spanContext());
        when(span.getName()).thenReturn(name);
        when(span.getAttributes()).thenReturn(attributes);
        when(span.toSpanData()).thenReturn(spanData);
        when(spanData.getAttributes()).thenReturn(attributes);
        when(spanData.getTraceId()).thenReturn(TRACE_ID);
        when(spanData.getSpanId()).thenReturn("0011223344556677");
        when(spanData.getName()).thenReturn(name);
        when(spanData.getStartEpochNanos()).thenReturn(startNanos);
        when(spanData.getEndEpochNanos()).thenReturn(startNanos + 1);
        when(spanData.getStatus()).thenReturn(StatusData.unset());
        when(spanData.getParentSpanContext()).thenReturn(SpanContext.getInvalid());
        return span;
    }

    private SpanContext spanContext() {
        return SpanContext.create(TRACE_ID, "0011223344556677", TraceFlags.getDefault(), TraceState.getDefault());
    }
}
