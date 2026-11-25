package ai.core.server.trace.service;

import ai.core.server.trace.web.ingest.IngestRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded hand-off queue for trace records. HTTP threads and the local span processor only enqueue;
 * a single consumer performs the actual store writes, so a slow trace database cannot occupy a web
 * worker. Records are at-least-once: the consumer retries a few times and counts the final failure.
 *
 * @author core-ai
 */
public class MemoryTraceSpanBuffer implements AutoCloseable {
    private static final Logger LOGGER = LoggerFactory.getLogger(MemoryTraceSpanBuffer.class);
    private static final int MAX_ATTEMPTS = 3;

    private final OTLPIngestService otlpIngestService;
    private final IngestService ingestService;
    private final int capacity;
    private final long maxBytes;
    private final long maxRecordBytes;
    private final BlockingQueue<Entry> queue;
    private final Object startLock = new Object();
    private final AtomicLong queuedBytes = new AtomicLong();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final AtomicLong rejected = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private volatile Thread worker;

    public MemoryTraceSpanBuffer(OTLPIngestService otlpIngestService, IngestService ingestService,
                                 int capacity, long maxBytes, long maxRecordBytes) {
        if (capacity <= 0) throw new IllegalArgumentException("capacity must be positive");
        if (maxBytes <= 0) throw new IllegalArgumentException("maxBytes must be positive");
        if (maxRecordBytes <= 0 || maxRecordBytes > maxBytes) {
            throw new IllegalArgumentException("maxRecordBytes must be positive and <= maxBytes");
        }
        this.otlpIngestService = otlpIngestService;
        this.ingestService = ingestService;
        this.capacity = capacity;
        this.maxBytes = maxBytes;
        this.maxRecordBytes = maxRecordBytes;
        this.queue = new ArrayBlockingQueue<>(capacity);
    }

    public void start() {
        synchronized (startLock) {
            if (worker != null || closing.get()) return;
            var thread = new Thread(this::consume, "trace-ingest-buffer");
            thread.setDaemon(true);
            thread.start();
            worker = thread;
        }
    }

    public boolean offerOtlp(ExportTraceServiceRequest request, long bytes) {
        return offer(() -> otlpIngestService.ingest(request), bytes, "otlp");
    }

    public boolean offerIngest(IngestRequest request, String authUserId, String source, long bytes) {
        return offer(() -> ingestService.ingest(request, authUserId, source), bytes, "ingest");
    }

    /**
     * Reserves the record against both queue bounds before handing it to the consumer. A false result
     * means the caller must report backpressure (HTTP 503) or count a local drop.
     */
    public boolean offer(Runnable task, long bytes, String kind) {
        if (task == null) return false;
        long recordBytes = bytes < 0 ? 0 : bytes;
        if (recordBytes > maxRecordBytes) {
            rejected.incrementAndGet();
            LOGGER.warn("trace record rejected: kind={}, bytes={}, maxRecordBytes={}", kind, recordBytes, maxRecordBytes);
            return false;
        }
        if (closing.get()) return false;

        long current = queuedBytes.addAndGet(recordBytes);
        if (current > maxBytes || queue.size() >= capacity) {
            queuedBytes.addAndGet(-recordBytes);
            rejected.incrementAndGet();
            return false;
        }
        if (!queue.offer(new Entry(task, recordBytes))) {
            queuedBytes.addAndGet(-recordBytes);
            rejected.incrementAndGet();
            return false;
        }
        return true;
    }

    private void consume() {
        while (!closing.get() || !queue.isEmpty()) {
            Entry entry;
            try {
                entry = queue.poll(100, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (entry == null) continue;
            try {
                execute(entry.task);
            } finally {
                queuedBytes.addAndGet(-entry.bytes);
            }
        }
    }

    private void execute(Runnable task) {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                task.run();
                return;
            } catch (RuntimeException e) {
                if (attempt == MAX_ATTEMPTS) {
                    failed.incrementAndGet();
                    LOGGER.error("trace ingest failed after {} attempts, dropping record", attempt, e);
                    return;
                }
                if (!sleepBeforeRetry(attempt)) {
                    failed.incrementAndGet();
                    LOGGER.warn("trace ingest interrupted, dropping record", e);
                    return;
                }
            }
        }
    }

    private boolean sleepBeforeRetry(int attempt) {
        try {
            Thread.sleep(100L * attempt);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * Stops accepting new records and waits for the in-flight queue to drain. The wait is bounded so
     * shutdown cannot outlive the database it is trying to flush.
     */
    public void shutdown(Duration timeout) {
        closing.set(true);
        var thread = worker;
        if (thread == null) return;
        try {
            thread.join(Math.max(1L, timeout.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (thread.isAlive()) {
            LOGGER.warn("trace ingest buffer did not drain within {}, pending={}, queuedBytes={}",
                    timeout, queue.size(), queuedBytes.get());
            thread.interrupt();
        }
    }

    @Override
    public void close() {
        shutdown(Duration.ofSeconds(10));
    }

    public long queuedBytes() {
        return queuedBytes.get();
    }

    public int depth() {
        return queue.size();
    }

    public long rejected() {
        return rejected.get();
    }

    public long failed() {
        return failed.get();
    }

    private record Entry(Runnable task, long bytes) {
    }
}
