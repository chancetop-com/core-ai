package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class SessionOwnershipRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionOwnershipRegistry.class);
    private static final String OWNER_KEY_PREFIX = "session:owner:";
    private static final Duration OWNERSHIP_TTL = Duration.ofSeconds(30);

    private final JedisPool jedisPool;
    private final String hostname;

    @SuppressFBWarnings("ITU_INAPPROPRIATE_TOSTRING_USE")
    public SessionOwnershipRegistry(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
        this.hostname = resolveHostname();
        LOGGER.info("SessionOwnershipRegistry initialized, hostname={}", hostname);
    }

    public boolean claim(String sessionId) {
        try (var jedis = jedisPool.getResource()) {
            var result = jedis.set(ownerKey(sessionId), hostname,
                    SetParams.setParams().nx().ex((int) OWNERSHIP_TTL.toSeconds()));
            var claimed = "OK".equals(result);
            if (claimed) {
                LOGGER.debug("claimed session ownership, sessionId={}", sessionId);
            }
            return claimed;
        } catch (Exception e) {
            LOGGER.warn("failed to claim session ownership (Redis unavailable), sessionId={}", sessionId, e);
            return false;
        }
    }

    public boolean renew(String sessionId) {
        try (var jedis = jedisPool.getResource()) {
            var result = jedis.set(ownerKey(sessionId), hostname,
                    SetParams.setParams().xx().ex((int) OWNERSHIP_TTL.toSeconds()));
            return "OK".equals(result);
        } catch (Exception e) {
            LOGGER.warn("failed to renew session ownership (Redis unavailable), sessionId={}", sessionId, e);
            return false;
        }
    }

    public boolean claimOrRenew(String sessionId) {
        if (renew(sessionId)) return true;
        return claim(sessionId);
    }

    public String getOwner(String sessionId) {
        try (var jedis = jedisPool.getResource()) {
            return jedis.get(ownerKey(sessionId));
        } catch (Exception e) {
            LOGGER.warn("failed to get session owner (Redis unavailable), sessionId={}", sessionId, e);
            return null;
        }
    }

    public boolean isOwner(String sessionId) {
        var owner = getOwner(sessionId);
        return hostname.equals(owner);
    }

    public void release(String sessionId) {
        try (var jedis = jedisPool.getResource()) {
            jedis.eval(
                    "if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end",
                    List.of(ownerKey(sessionId)),
                    List.of(hostname));
            LOGGER.debug("released session ownership, sessionId={}", sessionId);
        } catch (Exception e) {
            LOGGER.warn("failed to release session ownership (Redis unavailable), sessionId={}", sessionId, e);
        }
    }

    public String getHostname() {
        return hostname;
    }

    @SuppressFBWarnings("ITU_INAPPROPRIATE_TOSTRING_USE")
    private String resolveHostname() {
        var env = System.getenv("HOSTNAME");
        if (env != null && !env.isBlank()) return env;
        try {
            return InetAddress.getLocalHost().getHostName();
        } catch (UnknownHostException e) {
            return "unknown-" + UUID.randomUUID().toString().substring(0, 8);
        }
    }

    private String ownerKey(String sessionId) {
        return OWNER_KEY_PREFIX + sessionId;
    }
}
