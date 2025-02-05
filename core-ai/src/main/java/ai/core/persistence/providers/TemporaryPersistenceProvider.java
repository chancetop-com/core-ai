package ai.core.persistence.providers;

import ai.core.persistence.PersistenceProvider;
import core.framework.async.Executor;
import core.framework.inject.Inject;
import core.framework.web.exception.NotFoundException;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * @author stephen
 */
public class TemporaryPersistenceProvider implements PersistenceProvider {
    @Inject
    Executor executor;

    private final Map<String, String> persistence = new HashMap<>();

    @Override
    public void save(String id, String context) {
        persistence.put(id, context);
        executor.submit("clear-persistence", () -> persistence.remove(id), Duration.ofMinutes(15));
    }

    @Override
    public void clear() {
    }

    @Override
    public Optional<String> load(String id) {
        if (!persistence.containsKey(id)) {
            throw new NotFoundException("Persistence id not found, id = " + id);
        }
        return Optional.of(persistence.get(id));
    }
}
