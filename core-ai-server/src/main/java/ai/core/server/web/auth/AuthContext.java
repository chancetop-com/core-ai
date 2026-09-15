package ai.core.server.web.auth;

import core.framework.web.WebContext;

/**
 * @author stephen
 */
public final class AuthContext {
    public static final String USER_ID_KEY = "auth.userId";
    public static final String KEY_ID_KEY = "auth.keyId";
    public static final String SANDBOX_PRINCIPAL_KEY = "auth.sandboxPrincipal";

    public static String userId(WebContext context) {
        return (String) context.get(USER_ID_KEY);
    }

    public static String keyId(WebContext context) {
        return (String) context.get(KEY_ID_KEY);
    }

    /**
     * Present only on requests authenticated with a sandbox session token, and only on the
     * sandbox hub surface; {@code null} everywhere else (including unknown users of the hub).
     */
    public static SandboxPrincipal sandboxPrincipal(WebContext context) {
        return (SandboxPrincipal) context.get(SANDBOX_PRINCIPAL_KEY);
    }

    private AuthContext() {
    }
}
