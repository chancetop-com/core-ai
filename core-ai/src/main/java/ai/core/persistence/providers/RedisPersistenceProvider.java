package ai.core.persistence.providers;

import ai.core.persistence.PersistenceProvider;
import core.framework.inject.Inject;
import core.framework.redis.Redis;

import java.time.Duration;
import java.util.Optional;

/**
 * @author stephen
 */
public class RedisPersistenceProvider implements PersistenceProvider {
    @Inject
    Redis redis;

    @Override
    public void save(String id, String context) {
        redis.set(id, context, Duration.ofMinutes(30));
    }

    @Override
    public void clear() {

    }

    @Override
    public Optional<String> load(String id) {
        var context = redis.get(id);
        if (context == null) {
            return Optional.empty();
        }
        return Optional.of(context);
    }
}
