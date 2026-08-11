package ai.core.server.session;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandbox.LazySandbox;
import ai.core.server.sandbox.SandboxService;
import ai.core.server.sandbox.SandboxServiceDependencies;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import ai.core.server.skill.MongoSkillProvider;
import ai.core.server.skill.SkillArchiveBuilder;
import ai.core.server.skill.SkillService;
import ai.core.server.web.sse.SessionChannelService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@SuppressFBWarnings("NAB_NEEDLESS_BOOLEAN_CONSTANT_CONVERSION")
class AgentSessionManagerSnapshotLifecycleTest {
    @Test
    void enablingAfterDirtyAcquireArmsEpochBeforeReleaseCapture() {
        var enabled = new AtomicBoolean(false);
        var snapshotService = mock(SandboxSnapshotService.class);
        when(snapshotService.enabled()).thenAnswer(invocation -> enabled.get());
        when(snapshotService.beginEpoch("session-1")).thenReturn(9L);
        var provider = mock(SandboxProvider.class);
        var delegate = readySandbox();
        when(provider.acquire(any(), any(), any())).thenReturn(delegate);
        var sandboxService = new SandboxService(provider, enabledConfig(), null,
                new SandboxServiceDependencies(null, snapshotService, null, null, null));
        var manager = manager(sandboxService, snapshotService);
        try {
            var sandbox = (LazySandbox) sandboxService.createSessionSandbox(
                    enabledConfig(), "session-1", "user-1", null);
            sandbox.uploadFile("/workspace/changed.txt", "changed".getBytes(StandardCharsets.UTF_8));
            assertEquals(0L, sandbox.snapshotEpoch());
            assertTrue(sandbox.snapshotDirty());

            enabled.set(true);
            manager.closeSession("session-1");

            verify(snapshotService).beginEpoch("session-1");
            verify(snapshotService).captureBeforeRelease(
                    "session-1", "user-1", 9L, "10.0.0.1", 8080, "img:latest");
            verify(provider).release(delegate);
        } finally {
            sandboxService.shutdown();
        }
    }

    @Test
    void reattachedLiveSandboxCapturesOnImmediateRelease() {
        var snapshotService = mock(SandboxSnapshotService.class);
        when(snapshotService.enabled()).thenReturn(true);
        when(snapshotService.beginEpoch("session-1")).thenReturn(7L);
        var provider = mock(SandboxProvider.class);
        var delegate = readySandbox();
        when(provider.attach(eq("sandbox-1"), any(), eq("session-1"), eq("user-1")))
                .thenReturn(java.util.Optional.of(delegate));
        var sandboxService = new SandboxService(provider, enabledConfig(), null,
                new SandboxServiceDependencies(null, snapshotService, null, null, null));
        var manager = manager(sandboxService, snapshotService);
        try {
            var sandbox = (LazySandbox) sandboxService.reattachOrCreateSandbox(
                    "sandbox-1", enabledConfig(), "session-1", "user-1", null);
            assertEquals(7L, sandbox.snapshotEpoch());

            manager.closeSession("session-1");

            verify(snapshotService).captureBeforeRelease(
                    "session-1", "user-1", 7L, "10.0.0.1", 8080, "img:latest");
            verify(provider).release(delegate);
        } finally {
            sandboxService.shutdown();
        }
    }

    private AgentSessionManager manager(SandboxService sandboxService, SandboxSnapshotService snapshotService) {
        var manager = new AgentSessionManager();
        manager.sandboxService = sandboxService;
        manager.sandboxSnapshotService = snapshotService;
        manager.skillService = mock(SkillService.class);
        manager.mongoSkillProvider = mock(MongoSkillProvider.class);
        manager.skillArchiveBuilder = mock(SkillArchiveBuilder.class);
        manager.chatMessageService = mock(ChatMessageService.class);
        manager.sessionChannelService = mock(SessionChannelService.class);
        manager.sessionAgentHelper = mock(SessionAgentHelper.class);
        return manager;
    }

    private Sandbox readySandbox() {
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("sandbox-1");
        when(sandbox.getStatus()).thenReturn(SandboxStatus.READY);
        when(sandbox.ip()).thenReturn("10.0.0.1");
        when(sandbox.port()).thenReturn(8080);
        when(sandbox.image()).thenReturn("img:latest");
        return sandbox;
    }

    private SandboxConfig enabledConfig() {
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        return config;
    }
}
