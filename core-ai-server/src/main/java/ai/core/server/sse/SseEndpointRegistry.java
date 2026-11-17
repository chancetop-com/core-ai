package ai.core.server.sse;

import ai.core.sse.SseChannelInterceptor;
import ai.core.sse.SsePreflight;
import core.framework.http.HTTPMethod;
import core.framework.web.sse.ChannelListener;

/**
 * @author stephen
 */
public interface SseEndpointRegistry {
    <T> void register(HTTPMethod method, String path, Class<T> eventClass, ChannelListener<T> listener, boolean requireEventStreamAccept);

    void addInterceptor(SseChannelInterceptor interceptor);

    void preflight(HTTPMethod method, String path, SsePreflight preflight);
}
