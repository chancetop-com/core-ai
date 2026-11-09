package ai.core.server;

import ai.core.server.tool.MongoToolRegistryStore;
import ai.core.server.tool.PgToolRegistryStore;
import ai.core.server.tool.ToolRegistryStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ToolRegistryStoreModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(ToolRegistryStore.class, bind(PgToolRegistryStore.class));
        } else {
            bind(ToolRegistryStore.class, bind(MongoToolRegistryStore.class));
        }
    }
}
