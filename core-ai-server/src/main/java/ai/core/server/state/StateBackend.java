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
        return of(value, storeMode, "sys.state.backend");
    }

    public static StateBackend of(String value, String storeMode, String propertyName) {
        return switch (value) {
            case "redis" -> REDIS;
            case "postgres" -> {
                if (!"pg".equals(storeMode)) {
                    throw new IllegalArgumentException(propertyName + "=postgres requires sys.store.mode=pg");
                }
                yield POSTGRES;
            }
            default -> throw new IllegalArgumentException(propertyName + " must be redis or postgres, value=" + value);
        };
    }
}
