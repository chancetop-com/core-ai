package ai.core.server;

import ai.core.server.channel.ChannelConfigStore;
import ai.core.server.channel.MongoChannelConfigStore;
import ai.core.server.channel.PgChannelConfigStore;
import ai.core.server.channel.openclaw.MongoOcgConfigStore;
import ai.core.server.channel.openclaw.OcgConfigStore;
import ai.core.server.channel.openclaw.PgOcgConfigStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ChannelStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(ChannelConfigStore.class, bind(PgChannelConfigStore.class));
            bind(OcgConfigStore.class, bind(PgOcgConfigStore.class));
        } else {
            bind(ChannelConfigStore.class, bind(MongoChannelConfigStore.class));
            bind(OcgConfigStore.class, bind(MongoOcgConfigStore.class));
        }
    }
}
