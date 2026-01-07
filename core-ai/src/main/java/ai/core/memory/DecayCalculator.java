package ai.core.memory;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * @author xander
 */
public final class DecayCalculator {

    private static final double DEFAULT_DECAY_RATE = 0.02;

    public static double calculate(MemoryRecord record) {
        if (record == null || record.getLastAccessedAt() == null) {
            return 1.0;
        }

        Instant lastAccessedAt = record.getLastAccessedAt();
        long daysSinceAccess = ChronoUnit.DAYS.between(lastAccessedAt, Instant.now());
        if (daysSinceAccess < 0) {
            daysSinceAccess = 0;
        }

        return Math.exp(-DEFAULT_DECAY_RATE * daysSinceAccess);
    }

    private DecayCalculator() {
    }
}
