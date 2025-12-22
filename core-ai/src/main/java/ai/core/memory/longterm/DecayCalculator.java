package ai.core.memory.longterm;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Utility for calculating memory decay factors.
 * Uses exponential decay: decay = e^(-lambda * days)
 *
 * @author xander
 */
public final class DecayCalculator {

    private static final double DEFAULT_DECAY_RATE = 0.02;

    /**
     * Calculate decay factor for a memory record.
     * Based on time since last access and memory type's decay rate.
     *
     * @param record the memory record
     * @return decay factor between 0 and 1
     */
    public static double calculate(MemoryRecord record) {
        if (record == null || record.getLastAccessedAt() == null) {
            return 1.0;
        }

        MemoryType type = record.getType();
        double lambda = type != null ? type.getDecayRate() : DEFAULT_DECAY_RATE;

        Instant lastAccessedAt = record.getLastAccessedAt();
        long daysSinceAccess = ChronoUnit.DAYS.between(lastAccessedAt, Instant.now());
        if (daysSinceAccess < 0) {
            daysSinceAccess = 0;
        }

        return Math.exp(-lambda * daysSinceAccess);
    }

    private DecayCalculator() {
    }
}
