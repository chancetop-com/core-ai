package ai.core.server.trace.spi;

import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.TraceFlags;
import io.opentelemetry.api.trace.TraceState;
import io.opentelemetry.context.Context;
import io.opentelemetry.sdk.trace.ReadWriteSpan;
import io.opentelemetry.sdk.trace.data.SpanData;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.core.server.trace.service.OTLPIngestService;

import static org.junit.jupiter.api.Assertions.assertTrue;
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

    private ReadWriteSpan span(String name, Attributes attributes, long startNanos) {
        var span = mock(ReadWriteSpan.class);
        var spanData = mock(SpanData.class);
        when(span.getSpanContext()).thenReturn(spanContext());
        when(span.getName()).thenReturn(name);
        when(span.getAttributes()).thenReturn(attributes);
        when(span.toSpanData()).thenReturn(spanData);
        when(spanData.getStartEpochNanos()).thenReturn(startNanos);
        return span;
    }

    private SpanContext spanContext() {
        return SpanContext.create(TRACE_ID, "0011223344556677", TraceFlags.getDefault(), TraceState.getDefault());
    }
}
