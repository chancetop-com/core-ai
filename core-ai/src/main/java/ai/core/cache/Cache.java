package ai.core.cache;

/**
 * @author stephen
 */
public interface Cache {
    default boolean existed(String query) {
        return false;
    }

    default String load(String query) {
        return null;
    }
}
