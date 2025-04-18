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
}
