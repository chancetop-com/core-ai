package ai.core.server.session;

import ai.core.server.runtime.CapacityExceededException;
import ai.core.server.runtime.RuntimeLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.util.function.Supplier;

/**
 * Per-user live-session budget, counted in Redis because the sessions of one user may be spread over
 * several replicas (a local map would only ever see this pod's share). Fails open: a Redis outage must
 * not stop users from opening sessions, it only loses the fairness guarantee.
 *
 * @author stephen
 */
public class UserSessionBudget {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserSessionBudget.class);
    private static final String KEY_PREFIX = "coreai:rsess:";
    private static final int TTL_SECONDS = 24 * 3600;

    private final JedisPool jedisPool;
    // assigned by RuntimeGovernanceModule: read live from system settings
    public Supplier<RuntimeLimits> limitsSupplier;

    public UserSessionBudget(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    /** @throws CapacityExceededException when this user already holds the maximum number of live sessions. */
    public void acquire(String userId) {
        if (userId == null || userId.isBlank()) return;
        var limit = limit();
        if (limit <= 0) return;
        try (var jedis = jedisPool.getResource()) {
            var count = jedis.incr(key(userId));
            jedis.expire(key(userId), TTL_SECONDS);
            if (count > limit) {
                jedis.decr(key(userId));
                throw new CapacityExceededException("user_sessions", 30,
                        "session limit reached for this user (" + limit + "), close a session first");
            }
        } catch (CapacityExceededException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.warn("failed to count user sessions (Redis unavailable), allowing the session, userId={}", userId, e);
        }
    }

    /** Released when the session's runtime is torn down; never below zero, the TTL covers lost releases. */
    public void release(String userId) {
        if (userId == null || userId.isBlank()) return;
        if (limit() <= 0) return;
        try (var jedis = jedisPool.getResource()) {
            if (jedis.decr(key(userId)) < 0) jedis.del(key(userId));
        } catch (Exception e) {
            LOGGER.warn("failed to release user session budget (Redis unavailable), userId={}", userId, e);
        }
    }

    private int limit() {
        if (limitsSupplier == null) return 0;
        try {
            return limitsSupplier.get().maxSessionsPerUser;
        } catch (Exception e) {
            LOGGER.warn("failed to read runtime limits for the session budget, not limiting", e);
            return 0;
        }
    }

    private String key(String userId) {
        return KEY_PREFIX + userId;
    }
}
