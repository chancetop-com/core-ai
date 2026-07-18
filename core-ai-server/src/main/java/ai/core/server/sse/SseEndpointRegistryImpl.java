package ai.core.server.sse;

import ai.core.sse.PatchedServerSentEventConfig;
import ai.core.sse.SseChannelInterceptor;
import core.framework.http.HTTPMethod;
import core.framework.web.sse.ChannelListener;

import java.util.HashSet;
import java.util.Set;

/**
 * @author stephen
 */
public class SseEndpointRegistryImpl implements SseEndpointRegistry {
    public static final String CONFIG_NAME = "core-ai-server-sse";

    private final PatchedServerSentEventConfig config;
    private final Set<String> endpoints = new HashSet<>();

    public SseEndpointRegistryImpl(PatchedServerSentEventConfig config) {
        this.config = config;
    }

    @Override
    public <T> void register(HTTPMethod method, String path, Class<T> eventClass, ChannelListener<T> listener, boolean requireEventStreamAccept) {
        var endpoint = method + ":" + path;
        if (!endpoints.add(endpoint)) throw new IllegalStateException("duplicate SSE endpoint: " + endpoint);
        config.listen(method, path, eventClass, listener);
        if (requireEventStreamAccept) config.requireEventStreamAccept(method, path);
    }

    @Override
    public void addInterceptor(SseChannelInterceptor interceptor) {
        config.intercept(interceptor);
    }
}
