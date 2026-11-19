package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.UUID;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author stephen
 */
public class SessionOwnershipRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionOwnershipRegistry.class);

    private final OwnershipStore store;
    private final String hostname;

    @SuppressFBWarnings("ITU_INAPPROPRIATE_TOSTRING_USE")
    public SessionOwnershipRegistry(OwnershipStore store) {
        this.store = store;
        this.hostname = resolveHostname();
        LOGGER.info("SessionOwnershipRegistry initialized, hostname={}", hostname);
    }

    public boolean claim(String sessionId) {
        var claimed = store.claim(sessionId, hostname);
        if (claimed) {
            LOGGER.debug("claimed session ownership, sessionId={}", sessionId);
        }
        return claimed;
    }

    public boolean renew(String sessionId) {
        return store.renew(sessionId, hostname);
    }

    public boolean claimOrRenew(String sessionId) {
        if (renew(sessionId)) return true;
        return claim(sessionId);
    }

    public String getOwner(String sessionId) {
        return store.owner(sessionId);
    }

    public boolean isOwner(String sessionId) {
        return hostname.equals(getOwner(sessionId));
    }

    public void release(String sessionId) {
        store.release(sessionId, hostname);
        LOGGER.debug("released session ownership, sessionId={}", sessionId);
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
}
