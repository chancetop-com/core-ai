package ai.core.server.web;

import ai.core.api.server.settings.SystemSettingsView;
import ai.core.server.sandbox.snapshot.SandboxSnapshotPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
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
}
