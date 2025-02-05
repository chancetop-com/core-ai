package ai.core.persistence;

import java.util.Optional;

/**
 * @author stephen
 */
public interface PersistenceProvider {
    void save(String id, String context);

    void clear();

    Optional<String> load(String id);
}
