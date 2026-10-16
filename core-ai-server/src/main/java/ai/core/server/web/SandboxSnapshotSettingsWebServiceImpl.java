package ai.core.server.web;

import ai.core.api.server.settings.SandboxSnapshotSettingsRequest;
import ai.core.api.server.settings.SandboxSnapshotSettingsView;
import ai.core.api.server.settings.SandboxSnapshotSettingsWebService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.settings.SandboxSnapshotSettingsService;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.WebContext;

/**
 * @author xander
 */
@PermissionsRequired(PermissionCodes.SANDBOX_SNAPSHOT_MANAGE)
public class SandboxSnapshotSettingsWebServiceImpl implements SandboxSnapshotSettingsWebService {
    @Inject
    WebContext webContext;
    @Inject
    SandboxSnapshotSettingsService sandboxSnapshotSettingsService;

    @Override
    public SandboxSnapshotSettingsView get() {
        return sandboxSnapshotSettingsService.get(AuthContext.userId(webContext));
    }

    @Override
    public SandboxSnapshotSettingsView update(SandboxSnapshotSettingsRequest request) {
        return sandboxSnapshotSettingsService.update(request, AuthContext.userId(webContext));
    }
}
