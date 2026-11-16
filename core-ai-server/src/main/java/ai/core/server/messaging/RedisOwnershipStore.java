package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.List;

/**
 * @author stephen
 */
public class RedisOwnershipStore implements OwnershipStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisOwnershipStore.class);
    private static final String KEY_PREFIX = "session:owner:";
    private static final int TTL_SECONDS = 60;

    private final JedisPool jedisPool;

    public RedisOwnershipStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public boolean claim(String resourceId, String ownerPod) {
        try (var jedis = jedisPool.getResource()) {
            return "OK".equals(jedis.set(key(resourceId), ownerPod, SetParams.setParams().nx().ex(TTL_SECONDS)));
        } catch (Exception e) {
            LOGGER.warn("failed to claim ownership (Redis unavailable), resourceId={}", resourceId, e);
            return false;
        }
    }

    @Override
    public boolean renew(String resourceId, String ownerPod) {
        try (var jedis = jedisPool.getResource()) {
            return "OK".equals(jedis.set(key(resourceId), ownerPod, SetParams.setParams().xx().ex(TTL_SECONDS)));
        } catch (Exception e) {
            LOGGER.warn("failed to renew ownership (Redis unavailable), resourceId={}", resourceId, e);
            return false;
        }
    }

    @Override
    public String owner(String resourceId) {
        try (var jedis = jedisPool.getResource()) {
            return jedis.get(key(resourceId));
        } catch (Exception e) {
            LOGGER.warn("failed to get owner (Redis unavailable), resourceId={}", resourceId, e);
            return null;
        }
    }

    @Override
    public void release(String resourceId, String ownerPod) {
        try (var jedis = jedisPool.getResource()) {
            jedis.eval(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    List.of(key(resourceId)),
                    List.of(ownerPod));
        } catch (Exception e) {
            LOGGER.warn("failed to release ownership (Redis unavailable), resourceId={}", resourceId, e);
        }
    }

    private String key(String resourceId) {
        return KEY_PREFIX + resourceId;
    }
}
