package ai.core.utils;

import java.util.List;

/**
 * @author stephen
 */
public enum Platform {
    LINUX_X64("Linux", List.of("nix", "nux", "aix")),
    LINUX_ARM64("Linux", List.of("nix", "nux", "aix")),
    MACOS_X64("Mac", List.of("mac")),
    MACOS_ARM64("Mac", List.of("mac")),
    WINDOWS_X64("Windows", List.of("win")),
    WINDOWS_X86("Windows", List.of("win")),
    UNKNOWN("Unknown", List.of());

    private final String name;
    private final List<String> aliases;

    Platform(String name, List<String> aliases) {
        this.name = name;
        this.aliases = aliases;
    }

    public String getName() {
        return name;
    }

    public List<String> getAliases() {
        return aliases;
    }

    public boolean isWindows() {
        return this == WINDOWS_X64;
    }

    public boolean isMac() {
        return this == MACOS_X64;
    }

    public boolean isLinux() {
        return this == LINUX_X64;
    }

    public static Platform fromName(String name) {
        for (var type : values()) {
            if (type.aliases.stream().anyMatch(name::contains)) {
                return type;
            }
        }
        return UNKNOWN;
    }
}
