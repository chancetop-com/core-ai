package ai.core.server.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * Durable cross-pod session activity timestamps.
 * Terminal requests may land on a non-owner pod whose in-memory activity map
 * is invisible to the owner pod's idle cleanup; this Redis timestamp is the
 * only channel that survives load balancing.
 *
 * @author xander
 */
public class SessionActivityRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionActivityRegistry.class);
    private static final String KEY_PREFIX = "session-activity:";
    private static final long TTL_SECONDS = 4500;

    private final JedisPool jedisPool;

    public SessionActivityRegistry(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public void touch(String sessionId) {
        if (jedisPool == null) return;
        try (var jedis = jedisPool.getResource()) {
            jedis.set(key(sessionId), Long.toString(System.currentTimeMillis()), SetParams.setParams().ex(TTL_SECONDS));
        } catch (RuntimeException e) {
            LOGGER.warn("session activity touch failed, sessionId={}", sessionId, e);
        }
    }

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
