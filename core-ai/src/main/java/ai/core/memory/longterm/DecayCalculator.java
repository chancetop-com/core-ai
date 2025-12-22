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

    public static double calculate(MemoryRecord record) {
        if (record == null || record.getLastAccessedAt() == null) {
            return 1.0;
        }

        MemoryType type = record.getType();
        double lambda = type != null ? type.getDecayRate() : DEFAULT_DECAY_RATE;

        return calculate(record.getLastAccessedAt(), lambda);
    }

    public static double calculate(Instant lastAccessedAt, double lambda) {
        if (lastAccessedAt == null) {
            return 1.0;
        }

        long daysSinceAccess = ChronoUnit.DAYS.between(lastAccessedAt, Instant.now());
        if (daysSinceAccess < 0) {
            daysSinceAccess = 0;
        }

        return Math.exp(-lambda * daysSinceAccess);
    }

    public static double halfLifeDays(double lambda) {
        return Math.log(2) / lambda;
    }

    /**
     * Calculate decay rate for a desired half-life.
     * lambda = ln(2) / halfLife
     *
     * @param halfLifeDays desired half-life in days
     * @return decay rate
     */
    public static double decayRateForHalfLife(double halfLifeDays) {
        return Math.log(2) / halfLifeDays;
    }

    public static double daysUntilThreshold(double lambda, double threshold) {
        if (threshold <= 0 || threshold >= 1) {
            throw new IllegalArgumentException("Threshold must be between 0 and 1 (exclusive)");
        }
        return -Math.log(threshold) / lambda;
    }

    private DecayCalculator() {
    }
}
