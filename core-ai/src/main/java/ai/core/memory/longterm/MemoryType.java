package ai.core.memory.longterm;

/**
 * @author xander
 */
public enum MemoryType {

    FACT("Objective information about the user", 0.7, 0.02),

    PREFERENCE("User preferences and habits", 0.8, 0.015),

    GOAL("Long-term goals and intentions", 0.9, 0.01),

    EPISODE("Important interaction events", 0.6, 0.05),

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
