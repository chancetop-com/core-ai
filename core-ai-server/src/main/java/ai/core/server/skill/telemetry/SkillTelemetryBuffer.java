package ai.core.server.skill.telemetry;

import ai.core.skill.telemetry.SkillTelemetryIdentity;
import ai.core.skill.telemetry.SkillTelemetrySink;
import core.framework.inject.Inject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * In-memory per-replica accumulator for server-side skill runtime telemetry, flushed
 * periodically to PostgreSQL via lock-free atomic increments without blocking chat execution.
 *
 * @author stephen
 */
public class SkillTelemetryBuffer {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillTelemetryBuffer.class);

    @Inject
    SkillTelemetryStore telemetryStore;

    private final AtomicReference<ConcurrentMap<String, Bucket>> buckets = new AtomicReference<>(new ConcurrentHashMap<>());
    private final LongAdder unattributedScriptErrors = new LongAdder();

    public void recordInvoke(String skillId, String packageDigest) {
        var bucket = bucketFor(skillId, packageDigest);
        if (bucket == null) return;
        bucket.invoke.increment();
        bucket.touchInvokedAt(ZonedDateTime.now());
    }

    public void recordResourceRead(String skillId, String packageDigest) {
        var bucket = bucketFor(skillId, packageDigest);
        if (bucket == null) return;
        bucket.resourceRead.increment();
    }

    public void recordMaterializeFail(String skillId, String packageDigest) {
        var bucket = bucketFor(skillId, packageDigest);
        if (bucket == null) return;
        bucket.materializeFail.increment();
    }

    public void recordAttributedScriptCall(String skillId, String packageDigest, boolean error) {
        var bucket = bucketFor(skillId, packageDigest);
        if (bucket == null) return;
        bucket.attributedScriptCalls.increment();
        if (error) {
            bucket.attributedScriptErrors.increment();
        }
    }

    public void recordUnattributedScriptError() {
        unattributedScriptErrors.increment();
    }

    public long unattributedScriptErrorCount() {
        return unattributedScriptErrors.sum();
    }

    public SkillTelemetrySink asSink() {
        return new BufferSink();
    }

    public int flush() {
        var drained = drain();
        if (drained.isEmpty()) return 0;
        if (telemetryStore == null || !telemetryStore.isAvailable()) return 0;
        try {
            telemetryStore.recordDeltas(drained);
            return drained.size();
        } catch (RuntimeException e) {
            LOGGER.warn("failed to flush skill runtime stats, droppedBuckets={}", drained.size(), e);
            return 0;
        }
    }

    public List<SkillTelemetryDelta> drain() {
        var current = buckets.getAndSet(new ConcurrentHashMap<>());
        if (current.isEmpty()) return List.of();
        var deltas = new ArrayList<SkillTelemetryDelta>(current.size());
        for (var bucket : current.values()) {
            var delta = bucket.toDelta();
            if (!delta.isZero()) {
                deltas.add(delta);
            }
        }
        return deltas;
    }

    private Bucket bucketFor(String skillId, String packageDigest) {
        if (skillId == null || skillId.isBlank()) return null;
        var digest = packageDigest == null ? "" : packageDigest;
        var key = skillId + "\0" + digest;
        return buckets.get().computeIfAbsent(key, ignored -> new Bucket(skillId, digest));
    }

    private static final class Bucket {
        final String skillId;
        final String packageDigest;
        final LongAdder invoke = new LongAdder();
        final LongAdder resourceRead = new LongAdder();
        final LongAdder materializeFail = new LongAdder();
        final LongAdder attributedScriptCalls = new LongAdder();
        final LongAdder attributedScriptErrors = new LongAdder();
        final AtomicReference<ZonedDateTime> lastInvokedAt = new AtomicReference<>();

        Bucket(String skillId, String packageDigest) {
            this.skillId = skillId;
            this.packageDigest = packageDigest;
        }

        void touchInvokedAt(ZonedDateTime timestamp) {
            lastInvokedAt.accumulateAndGet(timestamp, (prev, next) -> {
                if (prev == null) return next;
                if (next == null) return prev;
                return next.isAfter(prev) ? next : prev;
            });
        }

        SkillTelemetryDelta toDelta() {
            return new SkillTelemetryDelta(
                    skillId,
                    packageDigest,
                    invoke.sum(),
                    resourceRead.sum(),
                    materializeFail.sum(),
                    attributedScriptCalls.sum(),
                    attributedScriptErrors.sum(),
                    lastInvokedAt.get());
        }
    }

    private final class BufferSink implements SkillTelemetrySink {
        @Override
        public void recordInvoke(SkillTelemetryIdentity identity) {
            if (identity != null) SkillTelemetryBuffer.this.recordInvoke(identity.skillId(), identity.packageDigest());
        }

        @Override
        public void recordResourceRead(SkillTelemetryIdentity identity) {
            if (identity != null) SkillTelemetryBuffer.this.recordResourceRead(identity.skillId(), identity.packageDigest());
        }

        @Override
        public void recordMaterializeFail(SkillTelemetryIdentity identity) {
            if (identity != null) SkillTelemetryBuffer.this.recordMaterializeFail(identity.skillId(), identity.packageDigest());
        }

        @Override
        public void recordAttributedScriptCall(SkillTelemetryIdentity identity, boolean error) {
            if (identity != null) {
                SkillTelemetryBuffer.this.recordAttributedScriptCall(identity.skillId(), identity.packageDigest(), error);
            }
        }

        @Override
        public void recordUnattributedScriptError() {
            SkillTelemetryBuffer.this.recordUnattributedScriptError();
        }
    }
}
