package ai.core.server;

import ai.core.api.server.settings.SystemSettingsWebService;
import ai.core.server.web.SystemSettingsWebServiceImpl;
import core.framework.module.Module;

/** Registers the settings API after its runtime policy dependencies are available. */
public class SystemSettingsWebModule extends Module {
    @Override
    protected void initialize() {
        api().service(SystemSettingsWebService.class, bind(SystemSettingsWebServiceImpl.class));
    }
}
