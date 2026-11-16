package ai.core.server.state;

/**
 * Distributed coordination state that used to live only in Redis. Each component switches
 * independently, and a PostgreSQL switch is only meaningful when the application database is
 * configured for the PostgreSQL store.
 *
 * @author stephen
 */
public enum StateBackend {
    REDIS,
    POSTGRES;

    public static StateBackend of(String value, String storeMode) {
        return switch (value) {
            case "redis" -> REDIS;
            case "postgres" -> {
                if (!"pg".equals(storeMode)) {
                    throw new IllegalArgumentException("sys.state.backend=postgres requires sys.store.mode=pg");
                }
                yield POSTGRES;
            }
            default -> throw new IllegalArgumentException("sys.state.backend must be redis or postgres, value=" + value);
        };
    }
}
