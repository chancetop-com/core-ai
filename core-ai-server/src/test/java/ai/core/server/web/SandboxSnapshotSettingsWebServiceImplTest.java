package ai.core.server.web;

import ai.core.api.server.settings.SandboxSnapshotSettingsRequest;
import ai.core.api.server.settings.SandboxSnapshotSettingsView;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.settings.SandboxSnapshotSettingsService;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.WebContext;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author xander
 */
class SandboxSnapshotSettingsWebServiceImplTest {
    @Test
    void usesDedicatedPermissionInsteadOfSystemManagement() {
        var permissions = SandboxSnapshotSettingsWebServiceImpl.class.getAnnotation(PermissionsRequired.class);

        assertArrayEquals(new String[]{PermissionCodes.SANDBOX_SNAPSHOT_MANAGE}, permissions.value());
    }

    @Test
    void readPassesAuthenticatedIdentityToService() {
        var service = createService();
        var view = new SandboxSnapshotSettingsView();
        view.retentionDays = 14;
        when(service.sandboxSnapshotSettingsService.get("selected-user")).thenReturn(view);

        assertSame(view, service.get());
        verify(service.sandboxSnapshotSettingsService).get("selected-user");
    }

    @Test
    void updatePassesAuthenticatedIdentityAndReturnsSavedConfiguration() {
        var service = createService();
        var request = new SandboxSnapshotSettingsRequest();
        request.retentionDays = BigDecimal.valueOf(30);
        var view = new SandboxSnapshotSettingsView();
        view.retentionDays = 30;
        when(service.sandboxSnapshotSettingsService.update(request, "selected-user")).thenReturn(view);

        assertSame(view, service.update(request));
        verify(service.sandboxSnapshotSettingsService).update(request, "selected-user");
    }

    private SandboxSnapshotSettingsWebServiceImpl createService() {
        var service = new SandboxSnapshotSettingsWebServiceImpl();
        service.webContext = mock(WebContext.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("selected-user");
        service.sandboxSnapshotSettingsService = mock(SandboxSnapshotSettingsService.class);
        return service;
    }
}
