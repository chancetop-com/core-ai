package ai.core.persistence.providers;

import ai.core.persistence.PersistenceProvider;

import java.util.Optional;

/**
 * @author stephen
 */
public class RedisPersistenceProvider implements PersistenceProvider {
    @Override
    public void save(String id, String context) {

    }

    @Override
    public void clear() {

    }

    @Override
    public Optional<String> load(String id) {
        return Optional.empty();
    }
}
