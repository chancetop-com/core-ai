package ai.core.server;

import ai.core.server.web.sse.ChannelService;
import ai.core.server.web.sse.SessionChannelService;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SseTransportModule extends Module {
    @Override
    protected void initialize() {
        bind(ChannelService.class);
        bind(SessionChannelService.class);
    }
}
