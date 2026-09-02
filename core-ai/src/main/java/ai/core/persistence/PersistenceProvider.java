package ai.core.persistence;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface PersistenceProvider {
    void save(String id, String context);

    void clear();

    void delete(List<String> ids);

    Optional<String> load(String id);

    /** Keys starting with prefix; stores that cannot enumerate return nothing (server-side drivers need a store that can). */
    default List<String> listIds(String prefix) {
        return List.of();
    }
}
