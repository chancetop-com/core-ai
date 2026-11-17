package ai.core.server.web;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Shared constant-time check for pod-to-pod endpoints.
 *
 * @author stephen
 */
public final class InternalTokenAuth {
    public static boolean matches(String provided, String token, String previousToken) {
        return matches(provided, token) || matches(provided, previousToken);
    }

    public static boolean matches(String provided, String expected) {
        if (provided == null || expected == null || provided.isEmpty() || expected.isEmpty()) return false;
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private InternalTokenAuth() {
    }
}
