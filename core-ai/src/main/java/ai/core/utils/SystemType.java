package ai.core.utils;

import java.util.List;

/**
 * @author stephen
 */
public enum SystemType {
    LIN("Linux", List.of("nix", "nux", "aix")),
    MAC("Mac", List.of("mac")),
    WIN("Windows", List.of("win")),
    UNKNOWN("Unknown", List.of());

    private final String name;
    private final List<String> aliases;

    SystemType(String name, List<String> aliases) {
        this.name = name;
        this.aliases = aliases;
    }

    public String getName() {
        return name;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public static SystemType fromName(String name) {
        for (var type : values()) {
            if (type.aliases.stream().anyMatch(name::contains)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
