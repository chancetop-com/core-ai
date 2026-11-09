package ai.core.server;

import ai.core.server.gateway.GatewayModelStore;
import ai.core.server.gateway.GatewayProviderStore;
import ai.core.server.gateway.MongoGatewayModelStore;
import ai.core.server.gateway.MongoGatewayProviderStore;
import ai.core.server.gateway.PgGatewayModelStore;
import ai.core.server.gateway.PgGatewayProviderStore;
import core.framework.module.Module;

/**
 * Binds gateway model/provider stores before settings, routing and trace modules inject them.
 *
 * @author stephen
 */
public class GatewayStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(GatewayProviderStore.class, bind(PgGatewayProviderStore.class));
            bind(GatewayModelStore.class, bind(PgGatewayModelStore.class));
        } else {
            bind(GatewayProviderStore.class, bind(MongoGatewayProviderStore.class));
            bind(GatewayModelStore.class, bind(MongoGatewayModelStore.class));
        }
    }
}
