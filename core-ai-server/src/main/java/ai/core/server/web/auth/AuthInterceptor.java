package ai.core.server.web.auth;

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

    @Override
    public Response intercept(Invocation invocation) throws Exception {
        var request = invocation.context().request();
        var path = request.path();

        if (!path.startsWith("/api/")) {
            return invocation.proceed();
        }

        if (path.equals("/api/auth/register") || path.equals("/api/auth/login")
                || path.startsWith("/api/public/otel/")
                || path.startsWith("/api/ingest/")
                || path.startsWith("/api/capabilities")
                || path.startsWith("/api/webhook-triggers/")) {
            return invocation.proceed();
        }

        var userId = requestAuthenticator.authenticate(request);
        invocation.context().put(AuthContext.USER_ID_KEY, userId);
        return invocation.proceed();
    }
}
