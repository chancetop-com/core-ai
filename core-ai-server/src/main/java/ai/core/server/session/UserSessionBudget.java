package ai.core.server.session;

import ai.core.server.runtime.CapacityExceededException;
import ai.core.server.runtime.RuntimeLimits;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.util.function.Supplier;

/**
 * Per-user live-session budget, counted in a shared store because the sessions of one user may be
 * spread over several replicas (a local map would only ever see this pod's share). Fails open: a
 * store outage must not stop users from opening sessions, it only loses the fairness guarantee.
 *
 * @author stephen
 */
public class UserSessionBudget {
    private static final Logger LOGGER = LoggerFactory.getLogger(UserSessionBudget.class);

    private final SessionBudgetStore store;
    // assigned by RuntimeGovernanceModule: read live from system settings
    public Supplier<RuntimeLimits> limitsSupplier;

    public UserSessionBudget(JedisPool jedisPool) {
        this(new RedisSessionBudgetStore(jedisPool));
    }

    public UserSessionBudget(SessionBudgetStore store) {
        this.store = store;
    }

    /** @throws CapacityExceededException when this user already holds the maximum number of live sessions. */
    public void acquire(String userId) {
        if (userId == null || userId.isBlank()) return;
        var limit = limit();
        if (limit <= 0) return;
        if (!store.acquire(userId, limit)) {
            throw new CapacityExceededException("user_sessions", 30,
                "session limit reached for this user (" + limit + "), close a session first");
        }
    }

    /** Released when the session's runtime is torn down; the store never counts below zero. */
    public void release(String userId) {
        if (userId == null || userId.isBlank()) return;
        if (limit() <= 0) return;
        store.release(userId);
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
}
