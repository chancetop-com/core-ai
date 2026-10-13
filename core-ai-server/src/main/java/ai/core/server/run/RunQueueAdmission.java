package ai.core.server.run;

import ai.core.server.runtime.RuntimeLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicLong;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

/**
 * Run-pool admission: how deep the accepted-but-not-started queue is, and how many runs were refused.
 * Kept out of {@link AgentRunner} (file-size budget) and behind a depth supplier so the pool owns
 * nothing but the executor.
 *
 * @author stephen
 */
public class RunQueueAdmission {
    private static final Logger LOGGER = LoggerFactory.getLogger(RunQueueAdmission.class);

    private final IntSupplier queueDepth;
    private final AtomicLong rejectedRuns = new AtomicLong();
    // assigned by RuntimeGovernanceModule: read live from system settings
    public Supplier<RuntimeLimits> limitsSupplier;

    public RunQueueAdmission(IntSupplier queueDepth) {
        this.queueDepth = queueDepth;
    }

    public int queued() {
        return queueDepth.getAsInt();
    }

    public long rejectedTotal() {
        return rejectedRuns.get();
    }

    public void recordRejection() {
        rejectedRuns.incrementAndGet();
    }

    /** Rejects before any sandbox or pool slot is taken: a full queue means "come back", not "wait". */
    public boolean queueFull() {
        var capacity = limits().runQueueCapacity;
        return capacity > 0 && queued() >= capacity;
    }

    public RuntimeLimits limits() {
        if (limitsSupplier == null) return new RuntimeLimits();
        try {
            return limitsSupplier.get();
        } catch (Exception e) {
            // settings come from Mongo: a transient failure must not take run admission down
            LOGGER.warn("failed to read runtime limits for run admission, allowing the run", e);
            return new RuntimeLimits();
        }
    }
}
