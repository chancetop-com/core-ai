package ai.core.server.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * @author stephen
 */
public class RedisSessionActivityStore implements SessionActivityStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisSessionActivityStore.class);
    private static final String KEY_PREFIX = "session-activity:";

    private final JedisPool jedisPool;

    public RedisSessionActivityStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public void touch(String sessionId) {
        if (jedisPool == null) return;
        try (var jedis = jedisPool.getResource()) {
            jedis.set(key(sessionId), Long.toString(System.currentTimeMillis()), SetParams.setParams().ex(TTL_SECONDS));
        } catch (RuntimeException e) {
            LOGGER.warn("session activity touch failed, sessionId={}", sessionId, e);
        }
    }

    @Override
    public long lastActivity(String sessionId) {
        if (jedisPool == null) return 0L;
        try (var jedis = jedisPool.getResource()) {
            var value = jedis.get(key(sessionId));
            return value == null ? 0L : Long.parseLong(value);
        } catch (RuntimeException e) {
            LOGGER.warn("session activity read failed, sessionId={}", sessionId, e);
            return 0L;
        }
    }

    private String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }
}
