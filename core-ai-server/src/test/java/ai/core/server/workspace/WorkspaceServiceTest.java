package ai.core.server.workspace;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

class WorkspaceServiceTest {

    @Test
    void disabledServiceFailsClosedWithoutTouchingPostgresStores() {
        var config = new WorkspaceConfig();
        config.enabled = false;
        var service = new WorkspaceService(new WorkspaceStores(null, null, null, null, null, null), null, null, config, Runnable::run);

        assertFalse(service.enabled());
        var error = assertThrows(WorkspaceException.class, () -> service.list("user-1"));
        assertEquals(WorkspaceException.UNSUPPORTED, error.errorCode);
        var wake = assertThrows(WorkspaceException.class, () -> service.wake("user-1", "workspace-1"));
        assertEquals(WorkspaceException.UNSUPPORTED, wake.errorCode);
    }
}
