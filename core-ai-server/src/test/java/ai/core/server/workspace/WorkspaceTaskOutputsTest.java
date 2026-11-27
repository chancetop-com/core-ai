package ai.core.server.workspace;

import ai.core.sandbox.Sandbox;
import ai.core.tool.ToolCallResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class WorkspaceTaskOutputsTest {
    @AfterEach
    void resetRetention() {
        WorkspaceTaskOutputs.retentionDays(7);
    }

    @Test
    void prunesOldOutputFilesWhenRetentionIsEnabled() {
        WorkspaceTaskOutputs.retentionDays(7);
        var sandbox = mock(Sandbox.class);
        when(sandbox.execute(eq("run_bash_command"), any(), isNull()))
                .thenReturn(ToolCallResult.completed("3\n"));

        int deleted = WorkspaceTaskOutputs.cleanup(sandbox);

        assertEquals(3, deleted);
        verify(sandbox).execute(eq("run_bash_command"),
                argThat(arguments -> arguments.contains(WorkspaceTaskOutputs.DIR)
                        && arguments.contains("-name '*.out'")
                        && arguments.contains("-mmin +10080")),
                isNull());
    }

    @Test
    void skipsCleanupWhenRetentionIsDisabled() {
        WorkspaceTaskOutputs.retentionDays(0);
        var sandbox = mock(Sandbox.class);

        int deleted = WorkspaceTaskOutputs.cleanup(sandbox);

        assertEquals(0, deleted);
        verifyNoInteractions(sandbox);
    }

    @Test
    void ignoresSandboxFailureWithoutThrowing() {
        WorkspaceTaskOutputs.retentionDays(7);
        var sandbox = mock(Sandbox.class);
        when(sandbox.execute(eq("run_bash_command"), any(), isNull()))
                .thenThrow(new RuntimeException("sandbox offline"));

        assertEquals(0, WorkspaceTaskOutputs.cleanup(sandbox));
    }
}
