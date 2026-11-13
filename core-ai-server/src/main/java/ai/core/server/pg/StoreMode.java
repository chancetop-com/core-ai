package ai.core.server.pg;

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

    private StoreMode() {
    }
}
