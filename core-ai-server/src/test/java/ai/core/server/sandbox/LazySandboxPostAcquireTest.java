package ai.core.server.sandbox;

import ai.core.api.server.session.SandboxEventType;
import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.time.ZonedDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LazySandboxPostAcquireTest {
    @Test
    @SuppressFBWarnings("NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION")
    void passesRestoredOutcomeToHookBeforeReadyEvent() {
        var manager = mock(SandboxManager.class);
        var sandbox = readySandbox();
        when(manager.acquire(any(), any(), any())).thenReturn(sandbox);
        var snapshotService = mock(SandboxSnapshotService.class);
        when(snapshotService.enabled()).thenReturn(true);
        var createdAt = ZonedDateTime.now().minusMinutes(1);
        when(snapshotService.restoreLatestWithMetadata("session-1", "user-1", "10.0.0.1", 8080))
                .thenReturn(new SandboxSnapshotService.RestoreResult(
                        SandboxSnapshotService.RestoreOutcome.RESTORED, createdAt));
        var outcome = new AtomicReference<SandboxSnapshotService.RestoreResult>();
        var steps = new ArrayList<String>();
        var lazy = new LazySandbox(new SandboxConfig(), manager,
                event -> {
                    if (event.type == SandboxEventType.READY) steps.add("ready");
                },
                new LazySandbox.SessionIdentity("session-1", "user-1"),
                value -> {
                    outcome.set(value);
                    steps.add("hook");
                }, snapshotService);

        lazy.ensureReady();

        assertEquals(SandboxSnapshotService.RestoreOutcome.RESTORED, outcome.get().outcome());
        assertEquals(createdAt, outcome.get().snapshotCreatedAt());
        assertEquals(java.util.List.of("hook", "ready"), steps);
    }

    @Test
    void disabledSnapshotPassesNoneOutcome() {
        var manager = mock(SandboxManager.class);
        var sandbox = readySandbox();
        when(manager.acquire(any(), any(), any())).thenReturn(sandbox);
        var outcome = new AtomicReference<SandboxSnapshotService.RestoreResult>();
        var lazy = new LazySandbox(new SandboxConfig(), manager, null,
                new LazySandbox.SessionIdentity("session-1", "user-1"), outcome::set, null);

        lazy.ensureReady();

        assertEquals(SandboxSnapshotService.RestoreOutcome.NONE, outcome.get().outcome());
    }

    private Sandbox readySandbox() {
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("sandbox-1");
        when(sandbox.getStatus()).thenReturn(SandboxStatus.READY);
        when(sandbox.ip()).thenReturn("10.0.0.1");
        when(sandbox.port()).thenReturn(8080);
        return sandbox;
    }
}
