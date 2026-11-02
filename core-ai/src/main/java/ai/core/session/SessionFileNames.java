package ai.core.session;

/**
 * File-name safety for session persistence keys. This is deliberately looser than the RPC-level
 * session id policy: subagent thread keys are derived from caller supplied task ids and may contain
 * characters outside {@code [A-Za-z0-9._~-]}. The only thing this layer must guarantee is that a key
 * can never escape the sessions directory or create an invalid file name on the current platform.
 *
 * @author stephen
 */
public final class SessionFileNames {
    private static final int MAX_LENGTH = 200;

    public static void check(String key) {
        if (key == null || key.isEmpty()) {
            throw new IllegalArgumentException("session key must not be empty");
        }
        if (key.length() > MAX_LENGTH) {
            throw new IllegalArgumentException("session key is too long: " + key.length());
        }
        if (key.startsWith(".")) {
            throw new IllegalArgumentException("session key must not start with '.': " + key);
        }
        if ("..".equals(key)) {
            throw new IllegalArgumentException("session key must not be '..'");
        }
        for (int i = 0; i < key.length(); i++) {
            var c = key.charAt(i);
            if (c == '/' || c == '\\' || Character.isISOControl(c)) {
                throw new IllegalArgumentException("session key contains an unsafe character: " + key);
            }
        }
    }

    private SessionFileNames() {
    }
}
