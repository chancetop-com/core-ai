package ai.core.server.web.auth;

import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.domain.User;
import ai.core.server.web.session.SessionIdentity;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.Interceptor;
import core.framework.web.Invocation;
import core.framework.web.Response;
import core.framework.web.exception.ForbiddenException;

/**
 * @author stephen
 */
public class AuthInterceptor implements Interceptor {
    private static final String API_USERS_PREFIX = "/api/api-users";
    private static final String ADMIN_API_USERS_PREFIX = "/api/admin/api-users";
    private static final String USER_TYPE_API = "api";

    public static boolean isPublicPath(String path) {
        return "/api/auth/register".equals(path) || "/api/auth/login".equals(path)
                || path.startsWith("/api/public/otel/")
                || path.startsWith("/api/public/artifacts/")
                || path.startsWith("/api/ingest/")
                || path.startsWith("/api/capabilities")
                || path.startsWith("/api/webhook-triggers/")
                || path.startsWith("/api/weclaw/");
    }

    public static boolean isAnonymousChannelPath(String path, ChannelConfigStore channelConfigStore) {
        return path.startsWith("/api/channels/") && channelAllowsAnonymous(path, channelConfigStore);
    }

    public static String extractChannelId(String path) {
        // path format: /api/channels/:channelId
        int start = "/api/channels/".length();
        if (start >= path.length()) return null;
        int end = path.indexOf('/', start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }

    private static boolean channelAllowsAnonymous(String path, ChannelConfigStore channelConfigStore) {
        var channelId = extractChannelId(path);
        if (channelId == null) return false;
        var channel = channelConfigStore.load(channelId);
        return channel != null && Boolean.FALSE.equals(channel.requireAuth);
    }

    @Inject
    RequestAuthenticator requestAuthenticator;

    @Inject
    ChannelConfigStore channelConfigStore;

    @Inject
    MongoCollection<User> userCollection;

    @Inject
    SessionIdentity sessionIdentity;

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        var path = request.path();

        if (!path.startsWith("/api/")) {
            return invocation.proceed();
        }

        if (isPublicPath(path) || isAnonymousChannelPath(path, channelConfigStore)) {
            return invocation.proceed();
        }

        if (path.startsWith(API_USERS_PREFIX)) {
            return interceptApiUsers(invocation);
        }

        // 1. session identity first (browser web login, stored in Redis when configured)
        var identity = sessionIdentity.getUserIdentityOrNull();
        if (identity != null) {
            invocation.context().put(AuthContext.USER_ID_KEY, identity.userId);
            return invocation.proceed();
        }

        // 2. bearer api key fallback (CLI / api users / non-cookie clients)
        var apiKeyResult = requestAuthenticator.authenticateFromApiKeyRecord(request);
        if (apiKeyResult != null) {
            invocation.context().put(AuthContext.USER_ID_KEY, apiKeyResult.userId());
            invocation.context().put(AuthContext.KEY_ID_KEY, apiKeyResult.keyId());
        } else {
            var userId = requestAuthenticator.authenticate(request);
            invocation.context().put(AuthContext.USER_ID_KEY, userId);
            // 3. persist identity into session for internal users so subsequent requests skip key lookup
            var user = userCollection.get(userId).orElse(null);
            if (user != null && !USER_TYPE_API.equals(user.userType)) {
                sessionIdentity.setLoginSession(userId);
            }
        }
        if (path.startsWith(ADMIN_API_USERS_PREFIX)) {
            requireAdmin(AuthContext.userId(invocation.context()));
        }
        return invocation.proceed();
    }

    private Response interceptApiUsers(Invocation invocation) throws Exception {
        var result = requestAuthenticator.authenticateFromApiKeyRecord(invocation.context().request());
        if (result == null || !"manage".equals(result.scope())) {
            throw new ForbiddenException("management key required");
        }
        invocation.context().put(AuthContext.USER_ID_KEY, result.userId());
        invocation.context().put(AuthContext.KEY_ID_KEY, result.keyId());
        return invocation.proceed();
    }

    private void requireAdmin(String userId) {
        var user = userCollection.get(userId)
                .orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) {
            throw new ForbiddenException("admin required");
        }
    }
}
