package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxConstants;
import ai.core.sandbox.SandboxProvider;
import org.junit.jupiter.api.Test;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
    void renewExtendsProviderLifetimeWithConfiguredTimeout() {
        var provider = mock(SandboxProvider.class);
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("claim-1");
        when(provider.acquire(any(), eq("s1"), eq("u1"))).thenReturn(sandbox);

        var config = new SandboxConfig();
        config.timeoutSeconds = 1200;

        var manager = new SandboxManager(provider);
        manager.acquire(config, "s1", "u1");
        manager.renew("claim-1");

        // renew must propagate to the provider so the externally-tracked deadline is extended
        verify(provider).renew(same(sandbox), eq(1200));
    }

    @Test
    void renewFallsBackToDefaultTimeoutWhenUnset() {
        var provider = mock(SandboxProvider.class);
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn("claim-2");
        when(provider.acquire(any(), eq("s2"), eq("u2"))).thenReturn(sandbox);

        var config = new SandboxConfig();
        config.timeoutSeconds = null;

        var manager = new SandboxManager(provider);
        manager.acquire(config, "s2", "u2");
        manager.renew("claim-2");

        verify(provider).renew(same(sandbox), eq(SandboxConstants.DEFAULT_TIMEOUT_SECONDS));
    }

    @Test
    void renewIgnoresUnknownSandbox() {
        var provider = mock(SandboxProvider.class);
        var manager = new SandboxManager(provider);

        manager.renew("missing");

        verify(provider, never()).renew(any(), anyInt());
    }
}
