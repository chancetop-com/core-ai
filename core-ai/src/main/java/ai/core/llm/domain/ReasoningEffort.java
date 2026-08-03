package ai.core.llm.domain;

import core.framework.api.json.Property;

import java.util.Locale;

/**
 * @author stephen
 */
public enum ReasoningEffort {
    @Property(name = "none")
    NONE,
    @Property(name = "low")
    LOW,
    @Property(name = "high")
    HIGH,
    @Property(name = "max")
    MAX;

    /**
     * Normalizes any vendor-specific level name to one of the supported levels.
     * Unknown values return null so callers can fall back to the provider default.
     */
    public static ReasoningEffort fromString(String value) {
        if (value == null || value.isBlank()) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "none", "off", "disabled", "disable", "no", "false" -> NONE;
            case "low", "lowest", "minimal", "minimum", "light", "fast" -> LOW;
            case "medium", "moderate", "balanced", "standard", "default", "normal", "high", "strong", "hard" -> HIGH;
            case "max", "maximum", "maximal", "highest", "xhigh", "extrahigh", "extra-high", "ultra", "extreme", "aggressive", "full" -> MAX;
            default -> null;
        };
    }
}
