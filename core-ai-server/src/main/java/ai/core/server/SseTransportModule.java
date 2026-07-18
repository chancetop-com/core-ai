package ai.core.server;

import ai.core.server.sse.SseEndpointRegistry;
import ai.core.server.sse.SseEndpointRegistryImpl;
import ai.core.server.web.sse.ChannelService;
import ai.core.server.web.sse.SessionChannelService;
import ai.core.sse.PatchedServerSentEventConfig;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SseTransportModule extends Module {
    @Override
    protected void initialize() {
        bind(ChannelService.class);
        bind(SessionChannelService.class);
        var config = config(PatchedServerSentEventConfig.class, SseEndpointRegistryImpl.CONFIG_NAME);
        bind(SseEndpointRegistry.class, new SseEndpointRegistryImpl(config));
    }
}
