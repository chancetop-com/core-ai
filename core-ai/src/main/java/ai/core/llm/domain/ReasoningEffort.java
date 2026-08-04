package ai.core.llm.domain;

import core.framework.api.json.Property;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

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

    private static final Map<String, ReasoningEffort> ALIASES = buildAliases();

    /**
     * Normalizes any vendor-specific level name to one of the supported levels.
     * Unknown values return null so callers can fall back to the provider default.
     */
    public static ReasoningEffort fromString(String value) {
        if (value == null || value.isBlank()) return null;
        return ALIASES.get(value.trim().toLowerCase(Locale.ROOT));
    }

    private static Map<String, ReasoningEffort> buildAliases() {
        var aliases = new HashMap<String, ReasoningEffort>();
        register(aliases, NONE, "none", "off", "disabled", "disable", "no", "false");
        register(aliases, LOW, "low", "lowest", "minimal", "minimum", "light", "fast");
        register(aliases, HIGH, "medium", "moderate", "balanced", "standard", "default", "normal", "high", "strong", "hard");
        register(aliases, MAX, "max", "maximum", "maximal", "highest", "xhigh", "extrahigh", "extra-high", "ultra", "extreme", "aggressive", "full");
        return aliases;
    }

    private static void register(Map<String, ReasoningEffort> aliases, ReasoningEffort effort, String... names) {
        for (var name : names) {
            aliases.put(name, effort);
        }
    }
}
