package ai.core.server.a2a;

import ai.core.a2a.A2ATaskState;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.time.Duration;

/**
 * Stores A2A task snapshots so any server Pod can answer task lookups and route
 * task mutations back to the Pod that owns the underlying AgentSession.
 *
 * @author xander
 */
public class A2ATaskRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(A2ATaskRegistry.class);
    private static final String TASK_KEY_PREFIX = "coreai:a2a:task:";
    private static final Duration ACTIVE_TASK_TTL = Duration.ofHours(2);
    private static final Duration TERMINAL_TASK_TTL = Duration.ofMinutes(30);

    private final JedisPool jedisPool;
    private final SessionOwnershipRegistry ownershipRegistry;

    protected A2ATaskRegistry() {
        this.jedisPool = null;
        this.ownershipRegistry = null;
    }

    public A2ATaskRegistry(JedisPool jedisPool, SessionOwnershipRegistry ownershipRegistry) {
        this.jedisPool = jedisPool;
        this.ownershipRegistry = ownershipRegistry;
    }

    public void save(A2ATaskState state) {
        if (state == null || state.taskId == null) return;
        var ownerPod = ownerPod(state.contextId);
        save(A2ATaskSnapshot.from(state, ownerPod));
    }

    public void save(A2ATaskSnapshot snapshot) {
        if (jedisPool == null || snapshot == null || snapshot.taskId == null) return;
        var ttl = snapshot.isTerminal() ? TERMINAL_TASK_TTL : ACTIVE_TASK_TTL;
        try (var jedis = jedisPool.getResource()) {
            jedis.setex(taskKey(snapshot.taskId), ttl.toSeconds(), JsonUtil.toJson(snapshot));
        } catch (Exception e) {
            LOGGER.warn("failed to save A2A task snapshot, taskId={}", snapshot.taskId, e);
        }
    }

    public A2ATaskSnapshot get(String taskId) {
        if (jedisPool == null || taskId == null || taskId.isBlank()) return null;
        try (var jedis = jedisPool.getResource()) {
            var json = jedis.get(taskKey(taskId));
            if (json == null || json.isBlank()) return null;
            return JsonUtil.fromJson(A2ATaskSnapshot.class, json);
        } catch (Exception e) {
            LOGGER.warn("failed to get A2A task snapshot, taskId={}", taskId, e);
            return null;
        }
    }

    public boolean isLocalOwner(A2ATaskSnapshot snapshot) {
        if (snapshot == null || ownershipRegistry == null) return true;
        if (snapshot.contextId != null && ownershipRegistry.isOwner(snapshot.contextId)) return true;
        return snapshot.ownerPod != null && snapshot.ownerPod.equals(ownershipRegistry.getHostname());
    }

    private String ownerPod(String contextId) {
        if (ownershipRegistry == null) return null;
        if (contextId != null && !contextId.isBlank()) {
            ownershipRegistry.claimOrRenew(contextId);
            var owner = ownershipRegistry.getOwner(contextId);
            if (owner != null && !owner.isBlank()) return owner;
        }
        return ownershipRegistry.getHostname();
    }

    private String taskKey(String taskId) {
        return TASK_KEY_PREFIX + taskId;
    }
}
