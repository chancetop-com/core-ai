package ai.core.memory.longterm;

/**
 * Types of long-term memory with different decay characteristics.
 *
 * @author xander
 */
public enum MemoryType {
    /**
     * Objective information about the user.
     * Example: "User is a Java developer"
     */
    FACT("Objective information about the user", 0.7, 0.02),

    /**
     * User preferences and habits.
     * Example: "Prefers concise code style"
     */
    PREFERENCE("User preferences and habits", 0.8, 0.015),

    /**
     * Long-term goals and intentions.
     * Example: "Wants to learn AI agent development"
     */
    GOAL("Long-term goals and intentions", 0.9, 0.01),

    /**
     * Important interaction events.
     * Example: "Helped user fix authentication bug"
     */
    EPISODE("Important interaction events", 0.6, 0.05),

    /**
     * Relationships mentioned by user.
     * Example: "User's colleague is named Tom"
     */
    RELATIONSHIP("Relationships mentioned by user", 0.75, 0.01);

    private final String description;
    private final double defaultImportance;
    private final double decayRate;

    MemoryType(String description, double defaultImportance, double decayRate) {
        this.description = description;
        this.defaultImportance = defaultImportance;
        this.decayRate = decayRate;
    }

    public String getDescription() {
        return description;
    }

    public double getDefaultImportance() {
        return defaultImportance;
    }

    public double getDecayRate() {
        return decayRate;
    }
}
