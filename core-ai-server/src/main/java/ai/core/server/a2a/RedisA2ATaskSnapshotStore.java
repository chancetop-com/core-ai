package ai.core.server.a2a;

import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.time.Duration;

/**
 * @author stephen
 */
public class RedisA2ATaskSnapshotStore implements A2ATaskSnapshotStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisA2ATaskSnapshotStore.class);
    private static final String TASK_KEY_PREFIX = "coreai:a2a:task:";

    private final JedisPool jedisPool;

    public RedisA2ATaskSnapshotStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public void save(A2ATaskSnapshot snapshot, Duration ttl) {
        if (jedisPool == null) return;
        try (var jedis = jedisPool.getResource()) {
            jedis.setex(taskKey(snapshot.taskId), ttl.toSeconds(), JsonUtil.toJson(snapshot));
        } catch (Exception e) {
            LOGGER.warn("failed to save A2A task snapshot, taskId={}", snapshot.taskId, e);
        }
    }

    @Override
    public A2ATaskSnapshot get(String taskId) {
        if (jedisPool == null) return null;
        try (var jedis = jedisPool.getResource()) {
            var json = jedis.get(taskKey(taskId));
            if (json == null || json.isBlank()) return null;
            return JsonUtil.fromJson(A2ATaskSnapshot.class, json);
        } catch (Exception e) {
            LOGGER.warn("failed to get A2A task snapshot, taskId={}", taskId, e);
            return null;
        }
    }

    private String taskKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }
}
