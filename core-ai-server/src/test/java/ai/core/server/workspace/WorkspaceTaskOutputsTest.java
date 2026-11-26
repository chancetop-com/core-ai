package ai.core.server.workspace;

import ai.core.sandbox.Sandbox;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class WorkspaceTaskOutputsTest {
    @AfterEach
    void resetRetention() {
        WorkspaceTaskOutputs.retentionDays(7);
    }

    @Test
    void prunesOldOutputFilesWhenRetentionIsEnabled() {
        WorkspaceTaskOutputs.retentionDays(7);
        var sandbox = mock(Sandbox.class);

        WorkspaceTaskOutputs.cleanup(sandbox);

        verify(sandbox).execute(eq("run_bash_command"),
                argThat(arguments -> arguments.contains(WorkspaceTaskOutputs.DIR) && arguments.contains("-mtime +7")),
                isNull());
    }

    @Test
    void skipsCleanupWhenRetentionIsDisabled() {
        WorkspaceTaskOutputs.retentionDays(0);
        var sandbox = mock(Sandbox.class);

        WorkspaceTaskOutputs.cleanup(sandbox);

        verifyNoInteractions(sandbox);
    }
}
