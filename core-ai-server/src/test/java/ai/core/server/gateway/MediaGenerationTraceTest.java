package ai.core.server.gateway;

import ai.core.server.domain.MediaJob;
import ai.core.server.trace.service.OTLPIngestService;
import ai.core.server.trace.spi.LocalSpanProcessorRegistry;
import ai.core.telemetry.TelemetryConfig;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.Span;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

class MediaGenerationTraceTest {
    private static final TelemetryConfig TELEMETRY = SpanTestTelemetry.INSTANCE;

    @Test
    void emitsSettledVideoGenerationAsMediaTrace() throws Exception {
        var exported = exportSpan(job -> {
            job.mediaType = "video";
            job.completedAt = ZonedDateTime.parse("2026-09-11T10:01:40Z");
        });

        assertEquals("Video generation", exported.getName());
        assertEquals(Duration.ofSeconds(100).toNanos(), exported.getEndTimeUnixNano() - exported.getStartTimeUnixNano());
        var attrs = spanAttributes(exported);
        assertEquals("video_generation", attrs.get("gen_ai.operation.name"));
        assertEquals("video", attrs.get("media.type"));
        assertEquals("5.0", attrs.get("media.units"));
        assertEquals("second", attrs.get("media.unit_type"));
        assertEquals("seedance-2", attrs.get("gen_ai.request.model"));
        assertEquals("seedance-2-5", attrs.get("media.resolved_model"));
        assertEquals("provider-1", attrs.get("media.provider_id"));
        assertEquals("job-1", attrs.get("media.job_id"));
        assertEquals("user-1", attrs.get("user.id"));
        assertEquals("session-1", attrs.get("session.id"));
        assertEquals("0.42", attrs.get("gen_ai.usage.cost_usd"));
        assertEquals("upstream", attrs.get("gen_ai.usage.cost_source"));
        assertEquals("seedance-2", attrs.get("gen_ai.usage.pricing_model_id"));
    }

    @Test
    void emitsImageGenerationWithFallbackModel() throws Exception {
        var exported = exportSpan(job -> {
            job.mediaType = "image";
            job.requestedModel = null;
        });

        assertEquals("Image generation", exported.getName());
        var attrs = spanAttributes(exported);
        assertEquals("image_generation", attrs.get("gen_ai.operation.name"));
        assertEquals("image", attrs.get("media.type"));
        assertEquals("seedance-2-5", attrs.get("gen_ai.request.model"));
        assertEquals("seedance-2-5", attrs.get("media.resolved_model"));
    }

    private Span exportSpan(Consumer<MediaJob> customize) throws Exception {
        var latch = new CountDownLatch(1);
        var exportRequest = new AtomicReference<ExportTraceServiceRequest>();
        var ingestService = mock(OTLPIngestService.class);
        doAnswer(invocation -> {
            exportRequest.set(invocation.getArgument(0));
            latch.countDown();
            return null;
        }).when(ingestService).ingest(any(ExportTraceServiceRequest.class));
        LocalSpanProcessorRegistry.register(ingestService);
        try {
            var job = job();
            customize.accept(job);
            MediaGenerationTrace.record(TELEMETRY, job);

            // span export runs on LocalSpanProcessor's async executor; under full-suite load the
            // single worker thread can be scheduled late, so allow a generous wait
            assertTrue(latch.await(15, TimeUnit.SECONDS), "span export timed out");
            return exportRequest.get().getResourceSpans(0).getScopeSpans(0).getSpans(0);
        } finally {
            LocalSpanProcessorRegistry.clear();
        }
    }

    private MediaJob job() {
        var job = new MediaJob();
        job.id = "job-1";
        job.userId = "user-1";
        job.sessionId = "session-1";
        job.providerId = "provider-1";
        job.mediaType = "video";
        job.requestedModel = "seedance-2";
        job.resolvedModel = "seedance-2-5";
        job.mediaUnits = 5D;
        job.mediaUnitType = "second";
        job.costUsd = 0.42D;
        job.costSource = "upstream";
        job.pricingModelId = "seedance-2";
        job.createdAt = ZonedDateTime.parse("2026-09-11T10:00:00Z");
        job.completedAt = ZonedDateTime.parse("2026-09-11T10:00:20Z");
        return job;
    }

    private Map<String, String> spanAttributes(Span span) {
        var attrs = new HashMap<String, String>();
        for (var kv : span.getAttributesList()) {
            var value = kv.getValue();
            if (value.hasStringValue()) attrs.put(kv.getKey(), value.getStringValue());
            else if (value.hasIntValue()) attrs.put(kv.getKey(), String.valueOf(value.getIntValue()));
            else if (value.hasDoubleValue()) attrs.put(kv.getKey(), String.valueOf(value.getDoubleValue()));
        }
        return attrs;
    }
}
