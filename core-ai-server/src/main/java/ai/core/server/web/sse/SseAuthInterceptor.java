package ai.core.server.web.sse;

import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.auth.RequestAuthenticator;
import ai.core.sse.SseChannelInterceptor;
import core.framework.web.Request;
import core.framework.web.WebContext;

/**
 * SSE auth interceptor that reuses the same {@link RequestAuthenticator} as the HTTP {@code AuthInterceptor}.
 * <p>
 * Registered via {@code PatchedServerSentEventConfig.intercept()}, runs before every SSE channel listener's
 * {@code onConnect} within a WebContext scope, so {@link AuthContext#userId(WebContext)} works downstream.
 *
 * @author core-ai
 */
public class SseAuthInterceptor implements SseChannelInterceptor {
    private final RequestAuthenticator requestAuthenticator;

    public SseAuthInterceptor(RequestAuthenticator requestAuthenticator) {
        this.requestAuthenticator = requestAuthenticator;
    }

    @Override
    public void onConnect(Request request, WebContext webContext) {
        var userId = requestAuthenticator.authenticate(request);
        webContext.put(AuthContext.USER_ID_KEY, userId);
    }
}
