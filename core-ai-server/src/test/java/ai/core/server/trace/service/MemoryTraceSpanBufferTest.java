package ai.core.server.trace.service;

import ai.core.server.trace.web.ingest.IngestStartRequest;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * @author core-ai
 */
class MemoryTraceSpanBufferTest {
    private final OTLPIngestService otlp = mock(OTLPIngestService.class);
    private final IngestService ingest = mock(IngestService.class);

    @Test
    void runsQueuedWorkAndDrainsOnShutdown() {
        var buffer = new MemoryTraceSpanBuffer(otlp, ingest, 10, 1024, 1024);
        buffer.start();
        var ran = new AtomicInteger();

        assertTrue(buffer.offer(ran::incrementAndGet, 10, "test"));
        buffer.shutdown(Duration.ofSeconds(2));

        assertEquals(1, ran.get());
        assertEquals(0, buffer.depth());
        assertEquals(0, buffer.queuedBytes());
    }

    @Test
    void rejectsWhenTheByteBudgetIsExhausted() throws Exception {
        var buffer = new MemoryTraceSpanBuffer(otlp, ingest, 10, 10, 10);
        buffer.start();
        var started = new CountDownLatch(1);
        var release = new CountDownLatch(1);

        assertTrue(buffer.offer(() -> {
            started.countDown();
            try {
                release.await(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, 10, "test"));
        assertTrue(started.await(2, TimeUnit.SECONDS));
        assertFalse(buffer.offer(() -> { }, 1, "test"));
        assertTrue(buffer.rejected() > 0);

        release.countDown();
        buffer.shutdown(Duration.ofSeconds(2));
    }

    @Test
    void rejectsRecordsAboveThePerRecordCeiling() {
        var buffer = new MemoryTraceSpanBuffer(otlp, ingest, 10, 100, 10);
        buffer.start();

        assertFalse(buffer.offer(() -> { }, 11, "test"));
        assertTrue(buffer.rejected() > 0);

        buffer.shutdown(Duration.ofSeconds(2));
    }

    @Test
    void retriesTransientFailures() {
        var buffer = new MemoryTraceSpanBuffer(otlp, ingest, 10, 1024, 1024);
        buffer.start();
        var attempts = new AtomicInteger();

        assertTrue(buffer.offer(() -> {
            if (attempts.incrementAndGet() < 3) throw new IllegalStateException("transient");
        }, 1, "test"));
        buffer.shutdown(Duration.ofSeconds(2));

        assertEquals(3, attempts.get());
        assertEquals(0, buffer.failed());
    }

    @Test
    void offerStartExecutesStartTraceAndSharesBudget() throws Exception {
        var buffer = new MemoryTraceSpanBuffer(otlp, ingest, 10, 128, 128);
        buffer.start();
        var request = new IngestStartRequest();
        request.traceId = "0123456789abcdef0123456789abcdef";

        assertTrue(buffer.offerStart(request, "user-1", "cli", 64));
        assertFalse(buffer.offerStart(request, "user-1", "cli", 129));
        buffer.shutdown(Duration.ofSeconds(2));

        verify(ingest).startTrace(request, "user-1", "cli");
        assertEquals(0, buffer.depth());
        assertEquals(0, buffer.queuedBytes());
        assertTrue(buffer.rejected() > 0);
    }
}
