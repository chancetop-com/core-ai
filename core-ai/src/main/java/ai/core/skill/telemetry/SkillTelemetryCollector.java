package ai.core.skill.telemetry;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Thread-safe in-memory aggregator for client and agent-side skill runtime telemetry.
 *
 * @author stephen
 */
public class SkillTelemetryCollector implements SkillTelemetrySink {
    private final AtomicReference<ConcurrentMap<String, Bucket>> buckets = new AtomicReference<>(new ConcurrentHashMap<>());
    private final LongAdder unattributedScriptErrors = new LongAdder();

    @Override
    public void recordInvoke(SkillTelemetryIdentity identity) {
        var bucket = bucketFor(identity);
        if (bucket == null) return;
        bucket.invoke.increment();
        bucket.touchInvokedAt(ZonedDateTime.now());
    }

    @Override
    public void recordResourceRead(SkillTelemetryIdentity identity) {
        var bucket = bucketFor(identity);
        if (bucket == null) return;
        bucket.resourceRead.increment();
    }

    @Override
    public void recordMaterializeFail(SkillTelemetryIdentity identity) {
        var bucket = bucketFor(identity);
        if (bucket == null) return;
        bucket.materializeFail.increment();
    }

    @Override
    public void recordAttributedScriptCall(SkillTelemetryIdentity identity, boolean error) {
        var bucket = bucketFor(identity);
        if (bucket == null) return;
        bucket.attributedScriptCalls.increment();
        if (error) {
            bucket.attributedScriptErrors.increment();
        }
    }

    @Override
    public void recordUnattributedScriptError() {
        unattributedScriptErrors.increment();
    }

    public long unattributedScriptErrorCount() {
        return unattributedScriptErrors.sum();
    }

    public List<SkillTelemetrySample> drain() {
        var current = buckets.getAndSet(new ConcurrentHashMap<>());
        if (current.isEmpty()) return List.of();
        var samples = new ArrayList<SkillTelemetrySample>(current.size());
        for (var bucket : current.values()) {
            var sample = bucket.toSample();
            if (!sample.isZero()) {
                samples.add(sample);
            }
        }
        return samples;
    }

    public List<SkillTelemetrySample> snapshot() {
        var current = buckets.get();
        if (current.isEmpty()) return List.of();
        var samples = new ArrayList<SkillTelemetrySample>(current.size());
        for (var bucket : current.values()) {
            var sample = bucket.toSample();
            if (!sample.isZero()) {
                samples.add(sample);
            }
        }
        return samples;
    }

    private Bucket bucketFor(SkillTelemetryIdentity identity) {
        if (identity == null || !identity.isValid()) return null;
        return buckets.get().computeIfAbsent(identity.bucketKey(), ignored -> new Bucket(identity));
    }

    private static final class Bucket {
        final SkillTelemetryIdentity identity;
        final LongAdder invoke = new LongAdder();
        final LongAdder resourceRead = new LongAdder();
        final LongAdder materializeFail = new LongAdder();
        final LongAdder attributedScriptCalls = new LongAdder();
        final LongAdder attributedScriptErrors = new LongAdder();
        final AtomicReference<ZonedDateTime> lastInvokedAt = new AtomicReference<>();

        Bucket(SkillTelemetryIdentity identity) {
            this.identity = identity;
        }

        void touchInvokedAt(ZonedDateTime timestamp) {
            lastInvokedAt.accumulateAndGet(timestamp, (prev, next) -> {
                if (prev == null) return next;
                if (next == null) return prev;
                return next.isAfter(prev) ? next : prev;
            });
        }

        SkillTelemetrySample toSample() {
            return new SkillTelemetrySample(
                    identity,
                    invoke.sum(),
                    resourceRead.sum(),
                    materializeFail.sum(),
                    attributedScriptCalls.sum(),
                    attributedScriptErrors.sum(),
                    lastInvokedAt.get());
        }
    }
}
