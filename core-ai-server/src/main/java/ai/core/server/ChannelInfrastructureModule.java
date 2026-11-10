package ai.core.server;

import ai.core.server.channel.ChannelRegistry;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ChannelInfrastructureModule extends Module {
    @Override
    protected void initialize() {
        bind(ChannelRegistry.class);
        // UserChannelTargetStore/SessionNoticeStore are bound by SmallCollectionStoreModule, which loads
        // before this module, because SessionModule (loaded in between) also reads them.
    }
}
