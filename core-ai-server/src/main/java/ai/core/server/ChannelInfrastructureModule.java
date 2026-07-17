package ai.core.server;

import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.ChannelRegistry;
import ai.core.server.channel.openclaw.OcgConfigStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ChannelInfrastructureModule extends Module {
    @Override
    protected void initialize() {
        bind(ChannelConfigStore.class);
        bind(OcgConfigStore.class);
        bind(ChannelRegistry.class);
    }
}
