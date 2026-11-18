package ai.core.server.messaging;

/**
 * @author stephen
 */
public enum CommandBackend {
    REDIS,
    POSTGRES;

    public static CommandBackend of(String value) {
        if (value == null || value.isBlank() || "redis".equalsIgnoreCase(value)) {
            return REDIS;
        }
        if ("postgres".equalsIgnoreCase(value)) {
            return POSTGRES;
        }
        throw new IllegalArgumentException("invalid sys.command.backend, expected redis|postgres but was " + value);
    }
}
