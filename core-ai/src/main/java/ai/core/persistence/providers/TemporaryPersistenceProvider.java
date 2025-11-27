package ai.core.persistence.providers;

import ai.core.persistence.PersistenceProvider;
import core.framework.async.Executor;
import core.framework.inject.Inject;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class TemporaryPersistenceProvider implements PersistenceProvider {
    @Inject
    Executor executor;

    private final Map<String, String> persistence = new ConcurrentHashMap<>();
    private final int ttl; // default 15 minutes

    public TemporaryPersistenceProvider() {
        this(15 * 60);
    }

    public TemporaryPersistenceProvider(int ttl) {
        this.ttl = ttl;
    }

    @Override
    public void save(String id, String context) {
        persistence.put(id, context);
        executor.submit("clear-persistence", () -> delete(List.of(id)), Duration.ofSeconds(ttl));
    }

    @Override
    public void clear() {
    }

    @Override
    public void delete(List<String> ids) {
        ids.forEach(persistence::remove);
    }

    @Override
    public Optional<String> load(String id) {
        if (!persistence.containsKey(id)) {
            return Optional.empty();
        }
        return Optional.of(persistence.get(id));
    }
}
