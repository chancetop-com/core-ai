package ai.core.server.web;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.api.server.settings.SystemSettingsView;
import ai.core.api.server.settings.SystemSettingsWebService;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author stephen
 */
public class SystemSettingsWebServiceImpl implements SystemSettingsWebService {
    @Inject
    WebContext webContext;
    @Inject
    SystemSettingsService systemSettingsService;

    @Override
    public SystemSettingsView get() {
        return systemSettingsService.get(userId());
    }

    @Override
    public SystemSettingsView update(SystemSettingsRequest request) {
        return systemSettingsService.update(request, userId());
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }
}
