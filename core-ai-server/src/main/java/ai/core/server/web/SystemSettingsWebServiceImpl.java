package ai.core.server.web;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.api.server.settings.SystemSettingsView;
import ai.core.api.server.settings.SystemSettingsWebService;
import ai.core.server.sandbox.snapshot.SandboxSnapshotPolicy;
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
    @Inject
    SandboxSnapshotPolicy sandboxSnapshotPolicy;

    @Override
    public SystemSettingsView get() {
        return addSnapshotStatus(systemSettingsService.get(userId()));
    }

    @Override
    public SystemSettingsView update(SystemSettingsRequest request) {
        return addSnapshotStatus(systemSettingsService.update(request, userId()));
    }

    SystemSettingsView addSnapshotStatus(SystemSettingsView view) {
        var status = sandboxSnapshotPolicy.status();
        view.sandboxSnapshotDeploymentAllowed = status.deploymentAllowed();
        view.sandboxSnapshotStorageReady = status.storageReady();
        view.sandboxSnapshotEffective = status.effective();
        return view;
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }
}
