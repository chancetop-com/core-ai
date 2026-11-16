package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

/**
 * @author stephen
 */
public class RedisTurnStateStore implements TurnStateStore {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisTurnStateStore.class);
    private static final String TURN_KEY_PREFIX = "session:turn:";
    private static final String USER_TURN_KEY_PREFIX = "coreai:rturn:";
    private static final int TURN_TTL_SECONDS = 90;
    private static final int USER_TURN_TTL_SECONDS = 30 * 60;

    private final JedisPool jedisPool;

    public RedisTurnStateStore(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    public void markRunning(String sessionId, String userId, String ownerPod) {
        writeTurn(sessionId);
        if (userId == null) return;
        try (var jedis = jedisPool.getResource()) {
            jedis.incr(userTurnKey(userId));
            jedis.expire(userTurnKey(userId), USER_TURN_TTL_SECONDS);
        } catch (Exception e) {
            LOGGER.warn("failed to count in-flight turns (Redis unavailable), userId={}", userId, e);
        }
    }

    @Override
    public void clear(String sessionId, String userId, String ownerPod) {
        try (var jedis = jedisPool.getResource()) {
            jedis.del(turnKey(sessionId));
            if (userId != null && jedis.decr(userTurnKey(userId)) < 0) jedis.del(userTurnKey(userId));
        } catch (Exception e) {
            LOGGER.warn("failed to clear turn state (Redis unavailable), sessionId={}", sessionId, e);
        }
    }

    @Override
    public void renew(String sessionId, String userId) {
        writeTurn(sessionId);
        if (userId == null) return;
        try (var jedis = jedisPool.getResource()) {
            jedis.expire(userTurnKey(userId), USER_TURN_TTL_SECONDS);
        } catch (Exception e) {
            LOGGER.warn("failed to renew in-flight turn count (Redis unavailable), userId={}", userId, e);
        }
    }

    @Override
    public TurnStateRegistry.TurnLiveness liveness(String sessionId) {
        try (var jedis = jedisPool.getResource()) {
            return jedis.get(turnKey(sessionId)) != null ? TurnStateRegistry.TurnLiveness.RUNNING : TurnStateRegistry.TurnLiveness.NOT_RUNNING;
        } catch (Exception e) {
            LOGGER.warn("failed to read turn state (Redis unavailable), sessionId={}", sessionId, e);
            return TurnStateRegistry.TurnLiveness.UNKNOWN;
        }
    }

    @Override
    public int inFlightTurns(String userId) {
        if (userId == null) return 0;
        try (var jedis = jedisPool.getResource()) {
            var value = jedis.get(userTurnKey(userId));
            return value == null ? 0 : Integer.parseInt(value);
        } catch (Exception e) {
            LOGGER.warn("failed to read in-flight turns (Redis unavailable), userId={}", userId, e);
            return 0;
        }
    }

    private void writeTurn(String sessionId) {
        try (var jedis = jedisPool.getResource()) {
            jedis.set(turnKey(sessionId), "running", SetParams.setParams().ex(TURN_TTL_SECONDS));
        } catch (Exception e) {
            LOGGER.warn("failed to write turn state (Redis unavailable), sessionId={}", sessionId, e);
        }
    }

    private String turnKey(String sessionId) {
        return TURN_KEY_PREFIX + sessionId;
    }

    private String userTurnKey(String userId) {
        return USER_TURN_KEY_PREFIX + userId;
    }
}
