package ai.core.server.web;

import ai.core.api.server.settings.SystemSettingsRequest;
import ai.core.api.server.settings.SystemSettingsView;
import ai.core.server.sandbox.snapshot.SandboxSnapshotPolicy;
import ai.core.server.settings.SystemSettingsService;
import ai.core.server.web.auth.AuthContext;
import core.framework.web.WebContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SystemSettingsWebServiceImplTest {
    @Test
    void enrichesSettingsViewWithSnapshotRuntimeStatus() {
        var service = new SystemSettingsWebServiceImpl();
        service.sandboxSnapshotPolicy = mock(SandboxSnapshotPolicy.class);
        when(service.sandboxSnapshotPolicy.status()).thenReturn(
                new SandboxSnapshotPolicy.Status(true, true, false, false));
        var view = new SystemSettingsView();
        view.sandboxSnapshotEnabled = Boolean.TRUE;

        service.addSnapshotStatus(view);

        assertEquals(Boolean.TRUE, view.sandboxSnapshotEnabled);
        assertEquals(Boolean.TRUE, view.sandboxSnapshotDeploymentAllowed);
        assertEquals(Boolean.FALSE, view.sandboxSnapshotStorageReady);
        assertEquals(Boolean.FALSE, view.sandboxSnapshotEffective);
    }

    @Test
    void getReturnsRequestedAndRuntimeSnapshotStatus() {
        var service = createService();
        var view = new SystemSettingsView();
        view.sandboxSnapshotEnabled = Boolean.TRUE;
        when(service.systemSettingsService.get("user-1")).thenReturn(view);
        when(service.sandboxSnapshotPolicy.status()).thenReturn(
                new SandboxSnapshotPolicy.Status(true, true, false, false));

        var response = service.get();

        assertEquals(Boolean.TRUE, response.sandboxSnapshotEnabled);
        assertEquals(Boolean.TRUE, response.sandboxSnapshotDeploymentAllowed);
        assertEquals(Boolean.FALSE, response.sandboxSnapshotStorageReady);
        assertEquals(Boolean.FALSE, response.sandboxSnapshotEffective);
        verify(service.systemSettingsService).get("user-1");
    }

    @Test
    void updateReturnsRequestedAndRuntimeSnapshotStatus() {
        var service = createService();
        var request = new SystemSettingsRequest();
        request.sandboxSnapshotEnabled = Boolean.TRUE;
        var view = new SystemSettingsView();
        view.sandboxSnapshotEnabled = Boolean.TRUE;
        when(service.systemSettingsService.update(request, "user-1")).thenReturn(view);
        when(service.sandboxSnapshotPolicy.status()).thenReturn(
                new SandboxSnapshotPolicy.Status(true, false, true, false));

        var response = service.update(request);

        assertEquals(Boolean.TRUE, response.sandboxSnapshotEnabled);
        assertEquals(Boolean.FALSE, response.sandboxSnapshotDeploymentAllowed);
        assertEquals(Boolean.TRUE, response.sandboxSnapshotStorageReady);
        assertEquals(Boolean.FALSE, response.sandboxSnapshotEffective);
        verify(service.systemSettingsService).update(request, "user-1");
    }

    private SystemSettingsWebServiceImpl createService() {
        var service = new SystemSettingsWebServiceImpl();
        service.webContext = mock(WebContext.class);
        when(service.webContext.get(AuthContext.USER_ID_KEY)).thenReturn("user-1");
        service.systemSettingsService = mock(SystemSettingsService.class);
        service.sandboxSnapshotPolicy = mock(SandboxSnapshotPolicy.class);
        return service;
    }
}
