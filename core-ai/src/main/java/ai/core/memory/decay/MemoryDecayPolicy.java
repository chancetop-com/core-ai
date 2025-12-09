package ai.core.memory.decay;

import ai.core.memory.model.MemoryEntry;

import java.time.Instant;

/**
 * Interface for memory decay policies.
 * Determines how memory strength decreases over time.
 *
 * @author xander
 */
public interface MemoryDecayPolicy {

    /**
     * Calculate the current strength of a memory entry.
     *
     * @param entry the memory entry
     * @param now   current timestamp
     * @return calculated strength (0.0 - 1.0)
     */
    double calculateStrength(MemoryEntry entry, Instant now);

    /**
     * Check if a memory should be removed based on strength.
     *
     * @param entry       the memory entry
     * @param minStrength minimum strength threshold
     * @return true if memory should be removed
     */
    default boolean shouldRemove(MemoryEntry entry, double minStrength) {
        return entry.getStrength() < minStrength;
    }
}
