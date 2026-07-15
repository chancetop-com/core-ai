package ai.core.server.sandbox;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

/**
 * Stores sandbox-to-session bindings in Redis for cross-pod reattach during session rebuild.
 *
 * @author stephen
 */
class SandboxRedisStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(SandboxRedisStore.class);
    private static final String KEY_PREFIX = "sandbox:";

    private static String key(String sessionId) {
        return KEY_PREFIX + sessionId;
    }

    private final JedisPool jedisPool;

    SandboxRedisStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    String getBinding(String sessionId) {
        if (jedisPool == null) return null;
        try (var jedis = jedisPool.getResource()) {
            return jedis.get(key(sessionId));
        } catch (Exception e) {
            LOGGER.warn("failed to get sandbox binding from Redis, sessionId={}", sessionId, e);
            return null;
        }
    }

    void saveBinding(String sessionId, String sandboxId) {
        if (jedisPool == null) return;
        try (var jedis = jedisPool.getResource()) {
            jedis.set(key(sessionId), sandboxId);
            LOGGER.debug("sandbox binding stored in Redis, sessionId={}, sandboxId={}", sessionId, sandboxId);
        } catch (Exception e) {
            LOGGER.warn("failed to store sandbox binding in Redis, sessionId={}", sessionId, e);
        }
    }

    void deleteBinding(String sessionId) {
        if (jedisPool == null) return;
        try (var jedis = jedisPool.getResource()) {
            jedis.del(key(sessionId));
            LOGGER.debug("sandbox binding deleted from Redis, sessionId={}", sessionId);
        } catch (Exception e) {
            LOGGER.warn("failed to delete sandbox binding from Redis, sessionId={}", sessionId, e);
        }
    }
}
