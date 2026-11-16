package ai.core.server.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

/**
 * @author stephen
 */
public class RedisSessionBudgetStore implements SessionBudgetStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisSessionBudgetStore.class);
    private static final String KEY_PREFIX = "coreai:rsess:";
    private static final int TTL_SECONDS = 24 * 3600;

    private final JedisPool jedisPool;

    public RedisSessionBudgetStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public boolean acquire(String userId, String podId, int limit) {
        try (var jedis = jedisPool.getResource()) {
            var count = jedis.incr(key(userId));
            jedis.expire(key(userId), TTL_SECONDS);
            if (count > limit) {
                jedis.decr(key(userId));
                return false;
            }
            return true;
        } catch (Exception e) {
            LOGGER.warn("failed to count user sessions (Redis unavailable), allowing the session, userId={}", userId, e);
            return true;
        }
    }

    @Override
    public void release(String userId, String podId) {
        try (var jedis = jedisPool.getResource()) {
            if (jedis.decr(key(userId)) < 0) jedis.del(key(userId));
        } catch (Exception e) {
            LOGGER.warn("failed to release user session budget (Redis unavailable), userId={}", userId, e);
        }
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
