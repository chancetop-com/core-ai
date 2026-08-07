package ai.core.server.web.auth;

import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.domain.User;
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

    @Inject
    RequestAuthenticator requestAuthenticator;

    @Inject
    ChannelConfigStore channelConfigStore;

    @Inject
    MongoCollection<User> userCollection;

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        var path = request.path();

        if (!path.startsWith("/api/")) {
            return invocation.proceed();
        }

        if (isPublicPath(path) || isAnonymousChannelPath(path)) {
            return invocation.proceed();
        }

        if (path.startsWith(API_USERS_PREFIX)) {
            return interceptApiUsers(invocation);
        }

        var apiKeyResult = requestAuthenticator.authenticateFromApiKeyRecord(request);
        if (apiKeyResult != null) {
            invocation.context().put(AuthContext.USER_ID_KEY, apiKeyResult.userId());
            invocation.context().put(AuthContext.KEY_ID_KEY, apiKeyResult.keyId());
        } else {
            var userId = requestAuthenticator.authenticate(request);
            invocation.context().put(AuthContext.USER_ID_KEY, userId);
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

    private boolean isPublicPath(String path) {
        return "/api/auth/register".equals(path) || "/api/auth/login".equals(path)
                || path.startsWith("/api/public/otel/")
                || path.startsWith("/api/public/artifacts/")
                || path.startsWith("/api/ingest/")
                || path.startsWith("/api/capabilities")
                || path.startsWith("/api/webhook-triggers/")
                || path.startsWith("/api/weclaw/");
    }

    private boolean isAnonymousChannelPath(String path) {
        return path.startsWith("/api/channels/") && channelAllowsAnonymous(path);
    }

    private boolean channelAllowsAnonymous(String path) {
        var channelId = extractChannelId(path);
        if (channelId == null) return false;
        var channel = channelConfigStore.load(channelId);
        return channel != null && Boolean.FALSE.equals(channel.requireAuth);
    }

    private String extractChannelId(String path) {
        // path format: /api/channels/:channelId
        int start = "/api/channels/".length();
        if (start >= path.length()) return null;
        int end = path.indexOf('/', start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }

    private void requireAdmin(String userId) {
        var user = userCollection.get(userId)
                .orElseThrow(() -> new ForbiddenException("admin required"));
        if (!"admin".equals(user.role)) {
            throw new ForbiddenException("admin required");
        }
    }
}
