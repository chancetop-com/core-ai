package ai.core.server;

import ai.core.api.server.settings.SystemSettingsWebService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.web.SystemSettingsWebServiceImpl;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class SettingsModule extends Module {
    @Override
    protected void initialize() {
        bind(SystemSettingsService.class);
        api().service(SystemSettingsWebService.class, bind(SystemSettingsWebServiceImpl.class));
    }
}
