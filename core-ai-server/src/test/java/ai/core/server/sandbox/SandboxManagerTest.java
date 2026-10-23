package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Xander
 */
class SandboxManagerTest {
    @Test
    void renewPassesAcquisitionConfigToProvider() {
        var provider = mock(SandboxProvider.class);
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("claim-1");
        when(provider.acquire(any(), eq("s1"), eq("u1"))).thenReturn(sandbox);

        var config = new SandboxConfig();
        config.timeoutSeconds = 1200;

        var manager = new SandboxManager(provider);
        manager.acquire(config, "s1", "u1");
        manager.renew("claim-1");

        // renew must propagate to the provider so the externally-tracked deadline is extended,
        // with the config the sandbox was acquired with — the provider resolves the lifetime so
        // a renewal can never write a deadline shorter than the one written at acquisition
        verify(provider).renew(same(sandbox), same(config));
    }

    @Test
    void renewDelegatesEvenWhenConfiguredTimeoutIsUnset() {
        var provider = mock(SandboxProvider.class);
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("claim-2");
        when(provider.acquire(any(), eq("s2"), eq("u2"))).thenReturn(sandbox);

        var config = new SandboxConfig();
        config.timeoutSeconds = null;

        var manager = new SandboxManager(provider);
        manager.acquire(config, "s2", "u2");
        manager.renew("claim-2");

        verify(provider).renew(same(sandbox), same(config));
    }

    @Test
    void renewIgnoresUnknownSandbox() {
        var provider = mock(SandboxProvider.class);
        var manager = new SandboxManager(provider);

        manager.renew("missing");

        verify(provider, never()).renew(any(), any());
    }

    @Test
    void cleanupExpiredInvokesBeforeReleaseHook() {
        var provider = mock(SandboxProvider.class);
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("claim-3");
        when(provider.acquire(any(), eq("s3"), eq("u3"))).thenReturn(sandbox);

        var config = new SandboxConfig();
        config.timeoutSeconds = 30;

        var manager = new SandboxManager(provider);
        manager.acquire(config, "s3", "u3");
        manager.getActiveSandboxes().get(0).createdAt = Instant.now().minusSeconds(60);

        var hookCalled = new AtomicBoolean();
        manager.cleanupExpired(entry -> {
            hookCalled.set(true);
            assertEquals("s3", entry.sessionId);
            verify(provider, never()).release(sandbox);
        });

        assertTrue(hookCalled.get());
        verify(provider).release(same(sandbox));
    }

    @Test
    void cleanupExpiredReleasesSandboxEvenWhenHookFails() {
        var provider = mock(SandboxProvider.class);
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("claim-4");
        when(provider.acquire(any(), eq("s4"), eq("u4"))).thenReturn(sandbox);

        var config = new SandboxConfig();
        config.timeoutSeconds = 30;

        var manager = new SandboxManager(provider);
        manager.acquire(config, "s4", "u4");
        manager.getActiveSandboxes().get(0).createdAt = Instant.now().minusSeconds(60);

        manager.cleanupExpired(entry -> {
            throw new IllegalStateException("capture failed");
        });

        verify(provider).release(same(sandbox));
    }
}
