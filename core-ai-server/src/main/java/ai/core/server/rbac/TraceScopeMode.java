package ai.core.server.rbac;

import core.framework.web.exception.BadRequestException;

/**
 * Trace data visibility configured per role: own traces, every API user's traces, or all traces.
 * {@code admin} is always {@link #ALL} and never configurable.
 *
 * @author stephen
 */
public enum TraceScopeMode {
    SELF("self"),
    API("api"),
    ALL("all");

    private final String value;

    TraceScopeMode(String value) {
        this.value = value;
    }

    /**
     * Lenient parse used when reading stored settings: unknown or blank values fall back to {@link #SELF}
     * so a hand-edited setting can never break permission checks.
     */
    public static TraceScopeMode fromStringOrSelf(String value) {
        var parsed = parse(value);
        return parsed != null ? parsed : SELF;
    }

    /**
     * Strict parse used for request validation.
     */
    public static TraceScopeMode fromString(String value) {
        var parsed = parse(value);
        if (parsed == null) throw new BadRequestException("invalid trace scope mode: " + value);
        return parsed;
    }

    private static TraceScopeMode parse(String value) {
        if (value == null || value.isBlank()) return null;
        var trimmed = value.trim();
        for (var mode : values()) {
            if (mode.value.equalsIgnoreCase(trimmed)) return mode;
        }
        return null;
    }

    public String value() {
        return value;
    }
}
