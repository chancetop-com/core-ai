package ai.core.sse;

import core.framework.web.Request;
import core.framework.web.WebContext;

/**
 * SSE channel interceptor, similar to {@link core.framework.web.Interceptor}
 * but for SSE connections where there is no controller and no {@link core.framework.web.Invocation} pattern.
 * <p>
 * Runs before {@link core.framework.web.sse.ChannelListener#onConnect}.
 * Throw an exception (e.g. UnauthorizedException) to reject the connection.
 *
 * @author core-ai
 */
@FunctionalInterface
public interface SseChannelInterceptor {
    /**
     * Called before the channel listener's onConnect.
     * WebContext is already initialized with the request.
     */
    void onConnect(Request request, WebContext webContext);
}
