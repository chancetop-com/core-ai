package ai.core.server.auth;

import core.framework.web.WebContext;

/**
 * @author stephen
 */
public final class AuthContext {
    public static final String USER_ID_KEY = "auth.userId";

    public static String userId(WebContext context) {
        return (String) context.get(USER_ID_KEY);
    }

    private AuthContext() {
    }
}
