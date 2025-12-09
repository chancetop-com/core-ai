package ai.core.memory.decay;

import ai.core.memory.model.MemoryEntry;

import java.time.Duration;
import java.time.Instant;

/**
 * Exponential decay policy for memory strength.
 * Strength decreases exponentially based on time since last access.
 *
 * <p>Formula:
 * strength = baseDecay * (1 + frequencyBoost * 0.3 + importanceBoost * 0.3)
 * where baseDecay = exp(-decayRate * daysSinceAccess / decayIntervalDays)
 *
 * @author xander
 */
public class ExponentialDecayPolicy implements MemoryDecayPolicy {
    private static final double DEFAULT_DECAY_RATE = 0.1;
    private static final long DEFAULT_DECAY_INTERVAL_DAYS = 1;

    private final double decayRate;
    private final Duration decayInterval;

    public ExponentialDecayPolicy() {
        this(DEFAULT_DECAY_RATE, Duration.ofDays(DEFAULT_DECAY_INTERVAL_DAYS));
    }

    public ExponentialDecayPolicy(double decayRate, Duration decayInterval) {
        this.decayRate = decayRate;
        this.decayInterval = decayInterval;
    }

    @Override
    public double calculateStrength(MemoryEntry entry, Instant now) {
        if (entry.getLastAccessedAt() == null) {
            return entry.getStrength();
        }

        // Calculate days since last access
        long daysSinceAccess = Duration.between(entry.getLastAccessedAt(), now).toDays();
        if (daysSinceAccess < 0) {
            daysSinceAccess = 0;
        }

        // Base exponential decay
        double decayIntervalDays = decayInterval.toDays();
        if (decayIntervalDays <= 0) decayIntervalDays = 1;
        double baseDecay = Math.exp(-decayRate * daysSinceAccess / decayIntervalDays);

        // Frequency boost: more accesses = slower decay
        double frequencyBoost = Math.log(1 + entry.getAccessCount()) / Math.log(10);

        // Importance boost: more important = slower decay
        double importanceBoost = entry.getImportance();

        // Combined strength (capped at 1.0)
        double strength = baseDecay * (1 + frequencyBoost * 0.3 + importanceBoost * 0.3);
        return Math.min(1.0, Math.max(0.0, strength));
    }

    /**
     * Get the decay rate.
     */
    public double getDecayRate() {
        return decayRate;
    }

    /**
     * Get the decay interval.
     */
    public Duration getDecayInterval() {
        return decayInterval;
    }
}
