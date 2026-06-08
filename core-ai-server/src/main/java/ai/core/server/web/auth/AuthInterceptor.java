package ai.core.server.web.auth;

import ai.core.server.channel.ChannelConfigStore;
import core.framework.inject.Inject;
import core.framework.web.Interceptor;
import core.framework.web.Invocation;
import core.framework.web.Response;

/**
 * @author stephen
 */
public class AuthInterceptor implements Interceptor {
    @Inject
    RequestAuthenticator requestAuthenticator;

    @Inject
    ChannelConfigStore channelConfigStore;

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        var path = request.path();

        if (!path.startsWith("/api/")) {
            return invocation.proceed();
        }

        if (path.equals("/api/auth/register") || path.equals("/api/auth/login")
                || path.startsWith("/api/public/otel/")
                || path.startsWith("/api/public/artifacts/")
                || path.startsWith("/api/ingest/")
                || path.startsWith("/api/capabilities")
                || path.startsWith("/api/webhook-triggers/")
                || path.startsWith("/api/weclaw/")) {
            return invocation.proceed();
        }

        // Per-channel auth control: channels with requireAuth=false skip authentication
        if (path.startsWith("/api/channels/")) {
            var channelId = extractChannelId(path);
            if (channelId != null) {
                var channel = channelConfigStore.load(channelId);
                if (channel != null && Boolean.FALSE.equals(channel.requireAuth)) {
                    return invocation.proceed();
                }
            }
        }

        var userId = requestAuthenticator.authenticate(request);
        invocation.context().put(AuthContext.USER_ID_KEY, userId);
        return invocation.proceed();
    }

    private String extractChannelId(String path) {
        // path format: /api/channels/:channelId
        int start = "/api/channels/".length();
        if (start >= path.length()) return null;
        int end = path.indexOf('/', start);
        return end < 0 ? path.substring(start) : path.substring(start, end);
    }
}
