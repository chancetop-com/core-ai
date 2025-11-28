package ai.core.memory;

/**
 * Memory type enumeration for identifying different memory layers.
 *
 * @author Xander
 */
public enum MemoryType {
    SHORT_TERM("Short-Term Memory"),
    MEDIUM_TERM("Medium-Term Memory"),
    LONG_TERM("Long-Term Memory");

    private final String displayName;

    MemoryType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
