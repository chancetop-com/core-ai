package ai.core.server.plugin;

import ai.core.api.server.PluginRegistryWebService;
import ai.core.api.server.plugin.PluginRegistryEntryView;
import ai.core.api.server.plugin.PluginRegistrySearchRequest;
import ai.core.api.server.plugin.PluginRegistrySearchResponse;
import ai.core.api.server.plugin.RegisterPluginRequest;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class PluginRegistryWebServiceImpl implements PluginRegistryWebService {
    @Inject
    PluginRegistryService registryService;

    @Inject
    WebContext webContext;

    @Override
    @PermissionsRequired(PermissionCodes.PLUGIN_VIEW)
    public PluginRegistrySearchResponse search(PluginRegistrySearchRequest request) {
        var effective = request != null ? request : new PluginRegistrySearchRequest();
        return registryService.search(effective.query, effective.offset, effective.limit);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PLUGIN_VIEW)
    public PluginRegistryEntryView get(String id) {
        return registryService.get(id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PLUGIN_MANAGE)
    public PluginRegistryEntryView register(RegisterPluginRequest request) {
        return registryService.register(AuthContext.userId(webContext), request);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PLUGIN_MANAGE)
    public void delete(String id) {
        registryService.delete(id);
    }
}
