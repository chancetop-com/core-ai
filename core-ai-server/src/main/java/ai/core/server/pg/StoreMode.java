package ai.core.server.pg;

import java.util.Optional;
import java.util.function.Function;

/**
 * Shared reading of the store switches. Every switch follows the same shape: auto follows
 * {@code sys.store.mode}, and pinning to pg requires the global mode to be pg as well.
 *
 * @author stephen
 */
public final class StoreMode {
    public static boolean postgres(String mode, String globalMode, String propertyName) {
        return switch (mode) {
            case "auto" -> "pg".equals(globalMode);
            case "mongo" -> false;
            case "pg" -> {
                if (!"pg".equals(globalMode)) {
                    throw new IllegalArgumentException(propertyName + "=pg requires sys.store.mode=pg");
                }
                yield true;
            }
            default -> throw new IllegalArgumentException(propertyName + " must be auto, mongo, or pg");
        };
    }

    /**
     * True when the runtime still needs a Mongo connection. In the FBR environments every switch
     * is pg/postgresql/auto, so this returns false and the Mongo client is never created.
     */
    public static boolean mongoRequired(Function<String, Optional<String>> property) {
        var globalMode = property.apply("sys.store.mode").orElse("mongo");
        if ("mongo".equals(globalMode)) return true;
        if (!"postgresql".equals(property.apply("sys.dataset.store").orElse("mongo"))) return true;
        if (Boolean.parseBoolean(property.apply("sys.dataset.migration.enabled").orElse("false"))) return true;
        return "mongo".equals(property.apply("sys.trace.span.store.mode").orElse("auto"))
            || "mongo".equals(property.apply("sys.trace.store.mode").orElse("auto"))
            || "mongo".equals(property.apply("sys.runtime.store.mode").orElse("auto"));
    }

    public static boolean postgresConfigured(Function<String, Optional<String>> property) {
        return "pg".equals(property.apply("sys.store.mode").orElse("mongo"))
            || "postgresql".equals(property.apply("sys.dataset.store").orElse("mongo"))
            || Boolean.parseBoolean(property.apply("sys.dataset.migration.enabled").orElse("false"));
    }

    private StoreMode() {
    }
}
