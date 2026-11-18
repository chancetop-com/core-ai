package ai.core.server.plugin;

import ai.core.api.server.PluginRegistryWebService;
import core.framework.module.Module;

/**
 * Desktop plugin registry: a thin read/write surface over the plugin metadata store. Follows the
 * skill store switch so mongo and pg deployments both work.
 *
 * @author stephen
 */
public class PluginRegistryModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(PluginRegistryStore.class, bind(PgPluginRegistryStore.class));
        } else {
            bind(PluginRegistryStore.class, bind(MongoPluginRegistryStore.class));
        }
        bind(PluginRegistryService.class);
        api().service(PluginRegistryWebService.class, bind(PluginRegistryWebServiceImpl.class));
    }
}
