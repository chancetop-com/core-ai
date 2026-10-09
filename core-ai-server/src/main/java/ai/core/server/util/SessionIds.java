package ai.core.server.util;

/**
 * Conventions for session ids the server mints itself, told apart from client-supplied ones by a
 * versioned prefix. Currently minted by the gateway when a terminal sends no session header: the
 * fingerprint is a hash of the conversation opening (see GatewaySupport).
 *
 * @author stephen
 */
public final class SessionIds {
    // versioned so a later fingerprint rule change cannot collide with ids minted by an earlier version
    private static final String DERIVED_PREFIX = "fp1-";

    public static String derivedFrom(String fingerprint) {
        return DERIVED_PREFIX + fingerprint;
    }

    public static boolean isDerived(String sessionId) {
        return sessionId != null && sessionId.startsWith(DERIVED_PREFIX);
    }

    private SessionIds() {
    }
}
