package ai.core.cli.hub.skill;

import ai.core.api.server.skillhub.SkillTelemetryBatchItemRequest;
import ai.core.api.server.skillhub.SkillTelemetryBatchRequest;
import ai.core.skill.telemetry.SkillTelemetryCollector;
import ai.core.skill.telemetry.SkillTelemetryIdentity;
import ai.core.skill.telemetry.SkillTelemetrySample;
import ai.core.skill.telemetry.SkillTelemetrySink;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Non-blocking asynchronous skill telemetry uploader for CLI sessions. Batches deltas up to 50 items
 * with idempotent UUID-sequence batchIds, persists failed sends into a bounded offline queue for
 * startup replay, and never blocks session exit when the server is slow or unreachable.
 *
 * @author stephen
 */
public class CliSkillTelemetryUploader implements SkillTelemetrySink {
    public static final int MAX_BATCH_ITEMS = 50;
    public static final long MAX_DELTA_PER_ITEM = 100L;
    public static final int DEFAULT_MEMORY_QUEUE_CAPACITY = 64;
    public static final int DEFAULT_OFFLINE_QUEUE_CAPACITY = 100;
    public static final Duration DEFAULT_HTTP_TIMEOUT = Duration.ofMillis(1500);
    public static final long PERIODIC_FLUSH_INTERVAL_MS = 60_000L;

    private static final Logger LOGGER = LoggerFactory.getLogger(CliSkillTelemetryUploader.class);

    public static Path defaultOfflineQueuePath() {
        return Path.of(System.getProperty("user.home"), ".core-ai", "skill-telemetry-queue.jsonl");
    }

    private final SkillTelemetryCollector collector = new SkillTelemetryCollector();
    private final Consumer<SkillTelemetryBatchRequest> sender;
    private final Path offlineQueueFile;
    private final int maxOfflineBatches;
    private final BlockingQueue<SkillTelemetryBatchRequest> pendingQueue;
    private final AtomicLong batchSequence = new AtomicLong(0L);
    private final AtomicLong droppedMemoryBatches = new AtomicLong(0L);
    private final AtomicLong droppedOfflineBatches = new AtomicLong(0L);
    private final AtomicLong lastFlushEpochMs = new AtomicLong(System.currentTimeMillis());
    private final AtomicBoolean workerStarted = new AtomicBoolean(false);
    private final Object offlineLock = new Object();

    public CliSkillTelemetryUploader(SkillHubClient hubClient, Path offlineQueueFile) {
        this(batch -> hubClient.recordTelemetryBatch(batch, DEFAULT_HTTP_TIMEOUT),
                offlineQueueFile, DEFAULT_MEMORY_QUEUE_CAPACITY, DEFAULT_OFFLINE_QUEUE_CAPACITY);
    }

    public CliSkillTelemetryUploader(Consumer<SkillTelemetryBatchRequest> sender,
                                     Path offlineQueueFile,
                                     int memoryQueueCapacity,
                                     int maxOfflineBatches) {
        this.sender = sender;
        this.offlineQueueFile = offlineQueueFile;
        this.maxOfflineBatches = Math.max(1, maxOfflineBatches);
        this.pendingQueue = new ArrayBlockingQueue<>(Math.max(1, memoryQueueCapacity));
    }

    @Override
    public void recordInvoke(SkillTelemetryIdentity identity) {
        collector.recordInvoke(identity);
        maybeFlushPeriodically();
    }

    @Override
    public void recordResourceRead(SkillTelemetryIdentity identity) {
        collector.recordResourceRead(identity);
        maybeFlushPeriodically();
    }

    @Override
    public void recordMaterializeFail(SkillTelemetryIdentity identity) {
        collector.recordMaterializeFail(identity);
        maybeFlushPeriodically();
    }

    @Override
    public void recordAttributedScriptCall(SkillTelemetryIdentity identity, boolean error) {
        collector.recordAttributedScriptCall(identity, error);
        maybeFlushPeriodically();
    }

    @Override
    public void recordUnattributedScriptError() {
        collector.recordUnattributedScriptError();
    }

    public long unattributedScriptErrorCount() {
        return collector.unattributedScriptErrorCount();
    }

    public long droppedMemoryBatchCount() {
        return droppedMemoryBatches.get();
    }

    public long droppedOfflineBatchCount() {
        return droppedOfflineBatches.get();
    }

    public void flushAsync() {
        lastFlushEpochMs.set(System.currentTimeMillis());
        var drained = collector.drain();
        if (drained.isEmpty()) return;
        var batches = buildBatches(drained);
        for (var batch : batches) {
            enqueue(batch);
        }
    }

    public void replayOfflineAsync() {
        var queued = readAndClearOfflineQueue();
        for (var batch : queued) {
            enqueue(batch);
        }
    }

    private void maybeFlushPeriodically() {
        long now = System.currentTimeMillis();
        long prev = lastFlushEpochMs.get();
        if (now - prev >= PERIODIC_FLUSH_INTERVAL_MS && lastFlushEpochMs.compareAndSet(prev, now)) {
            flushAsync();
        }
    }

    private void enqueue(SkillTelemetryBatchRequest batch) {
        startWorkerIfNeeded();
        if (!pendingQueue.offer(batch)) {
            pendingQueue.poll();
            droppedMemoryBatches.incrementAndGet();
            if (!pendingQueue.offer(batch)) {
                persistOffline(batch);
            }
        }
    }

    private void startWorkerIfNeeded() {
        if (!workerStarted.compareAndSet(false, true)) return;
        var worker = new Thread(this::drainLoop, "skill-telemetry-uploader");
        worker.setDaemon(true);
        worker.start();
    }

    private void drainLoop() {
        while (!Thread.currentThread().isInterrupted()) {
            try {
                var batch = pendingQueue.take();
                sendOrQueueOffline(batch);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException e) {
                LOGGER.debug("skill telemetry worker caught unexpected error: {}", e.getMessage());
            }
        }
    }

    private void sendOrQueueOffline(SkillTelemetryBatchRequest batch) {
        try {
            sender.accept(batch);
        } catch (RuntimeException e) {
            LOGGER.debug("skill telemetry upload failed, queuing offline, batchId={}: {}", batch.batchId, e.getMessage());
            persistOffline(batch);
        }
    }

    private List<SkillTelemetryBatchRequest> buildBatches(List<SkillTelemetrySample> samples) {
        var items = new ArrayList<SkillTelemetryBatchItemRequest>(samples.size());
        for (var sample : samples) {
            appendSplitItems(items, sample);
        }
        var batches = new ArrayList<SkillTelemetryBatchRequest>((items.size() + MAX_BATCH_ITEMS - 1) / MAX_BATCH_ITEMS);
        for (int i = 0; i < items.size(); i += MAX_BATCH_ITEMS) {
            var slice = items.subList(i, Math.min(i + MAX_BATCH_ITEMS, items.size()));
            var batch = new SkillTelemetryBatchRequest();
            batch.batchId = UUID.randomUUID() + "-" + batchSequence.incrementAndGet();
            batch.items = List.copyOf(slice);
            batches.add(batch);
        }
        return batches;
    }

    private void appendSplitItems(List<SkillTelemetryBatchItemRequest> items, SkillTelemetrySample sample) {
        long inv = sample.invokeDelta();
        long read = sample.resourceReadDelta();
        long matFail = sample.materializeFailDelta();
        long calls = sample.attributedScriptCallsDelta();
        long errors = sample.attributedScriptErrorsDelta();
        while (inv > 0 || read > 0 || matFail > 0 || calls > 0 || errors > 0) {
            var item = new SkillTelemetryBatchItemRequest();
            item.skillId = sample.identity().skillId();
            item.qualifiedName = sample.identity().qualifiedName();
            item.packageDigest = sample.identity().packageDigest();
            item.invokeDelta = Math.min(inv, MAX_DELTA_PER_ITEM);
            item.resourceReadDelta = Math.min(read, MAX_DELTA_PER_ITEM);
            item.materializeFailDelta = Math.min(matFail, MAX_DELTA_PER_ITEM);
            item.attributedScriptCallsDelta = Math.min(calls, MAX_DELTA_PER_ITEM);
            item.attributedScriptErrorsDelta = Math.min(errors, MAX_DELTA_PER_ITEM);
            item.lastInvokedAt = sample.lastInvokedAt();
            items.add(item);

            inv -= item.invokeDelta;
            read -= item.resourceReadDelta;
            matFail -= item.materializeFailDelta;
            calls -= item.attributedScriptCallsDelta;
            errors -= item.attributedScriptErrorsDelta;
        }
    }

    private void persistOffline(SkillTelemetryBatchRequest batch) {
        if (offlineQueueFile == null) return;
        synchronized (offlineLock) {
            try {
                var lines = new ArrayDeque<String>();
                if (Files.isRegularFile(offlineQueueFile)) {
                    for (var line : Files.readAllLines(offlineQueueFile, StandardCharsets.UTF_8)) {
                        if (!line.isBlank()) lines.addLast(line);
                    }
                }
                lines.addLast(JsonUtil.toJson(batch));
                while (lines.size() > maxOfflineBatches) {
                    lines.removeFirst();
                    droppedOfflineBatches.incrementAndGet();
                }
                var parent = offlineQueueFile.getParent();
                if (parent != null) Files.createDirectories(parent);
                Files.write(offlineQueueFile, lines, StandardCharsets.UTF_8);
            } catch (IOException | RuntimeException e) {
                LOGGER.debug("failed to persist offline skill telemetry queue: {}", e.getMessage());
            }
        }
    }

    private List<SkillTelemetryBatchRequest> readAndClearOfflineQueue() {
        if (offlineQueueFile == null) return List.of();
        synchronized (offlineLock) {
            if (!Files.isRegularFile(offlineQueueFile)) return List.of();
            try {
                var lines = Files.readAllLines(offlineQueueFile, StandardCharsets.UTF_8);
                Files.deleteIfExists(offlineQueueFile);
                var result = new ArrayList<SkillTelemetryBatchRequest>(lines.size());
                for (var line : lines) {
                    var parsed = parseOfflineLine(line);
                    if (parsed != null) {
                        result.add(parsed);
                    }
                }
                return result;
            } catch (IOException e) {
                LOGGER.debug("failed to read offline skill telemetry queue: {}", e.getMessage());
                return List.of();
            }
        }
    }

    private SkillTelemetryBatchRequest parseOfflineLine(String line) {
        if (line == null || line.isBlank()) return null;
        try {
            var parsed = JsonUtil.fromJson(SkillTelemetryBatchRequest.class, line);
            if (parsed != null && parsed.batchId != null && parsed.items != null && !parsed.items.isEmpty()) {
                return parsed;
            }
            return null;
        } catch (RuntimeException ignored) {
            LOGGER.debug("skipping malformed offline telemetry queue line");
            return null;
        }
    }
}
