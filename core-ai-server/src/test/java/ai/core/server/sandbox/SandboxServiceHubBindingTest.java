package ai.core.server.sandbox;

import ai.core.sandbox.Sandbox;
import ai.core.sandbox.SandboxBinding;
import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.sandbox.SandboxStatus;
import ai.core.server.sandboxhub.SessionTokenService;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Binding checks of the sandbox hub. A hub request can land on any replica, so the pod that answers
 * must accept the token exactly when the session's sandbox still is the one the token names — even
 * when that session lives somewhere else, and never after the sandbox was replaced or released.
 *
 * @author xander
 */
class SandboxServiceHubBindingTest {
    private static final String SESSION_ID = "s1";

    @Test
    void hubBindingThatWasNeverWiredRejectsEveryToken() {
        var service = new SandboxService(mock(JedisPool.class), null, null, null);

        assertFalse(service.isBound(SESSION_ID, "sb-1"));
    }

    @Test
    void sandboxOfASessionLivingElsewhereIsAcceptedFromTheStoredBinding() {
        var service = redisBackedService("sb-1");

        assertTrue(service.isBound(SESSION_ID, "sb-1"));
    }

    @Test
    void replacedOrReleasedSandboxInvalidatesTheToken() {
        assertFalse(redisBackedService("sb-2").isBound(SESSION_ID, "sb-1"));
        assertFalse(redisBackedService(null).isBound(SESSION_ID, "sb-1"));
    }

    @Test
    void localSandboxIsTheTruthForItsOwnSession() {
        var service = enabledService(mock(JedisPool.class));
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        service.createSessionSandbox(config, SESSION_ID, "u1", "menu-agent", event -> {
        });

        // the session's sandbox is not ready on this pod yet, so no stored binding may vouch for it
        assertFalse(service.isBound(SESSION_ID, "sb-1"));
    }

    @Test
    void incompleteIdentityIsRejected() {
        var service = redisBackedService("sb-1");

        assertFalse(service.isBound(null, "sb-1"));
        assertFalse(service.isBound(SESSION_ID, null));
        assertFalse(service.isBound(SESSION_ID, " "));
    }

    @Test
    void attachingASandboxHandsTheHubIdentityToItsRuntime() {
        var attached = readySandbox("sb-1");
        var provider = mock(SandboxProvider.class);
        when(provider.attach(eq("sb-1"), any(), eq(SESSION_ID), eq("u1"))).thenReturn(Optional.of(attached));
        var service = hubEnabledService(provider);

        service.attachSandbox("sb-1", enabledConfig(), SESSION_ID, "u1", false);

        verify(attached).bind(any(SandboxBinding.class));
    }

    @Test
    void renewingRebindsARuntimeThatLostTheIdentity() {
        var sandbox = readySandbox("sb-1");
        when(sandbox.hubBound()).thenReturn(Boolean.FALSE);
        var service = hubEnabledService(providerAcquiring(sandbox));
        service.createSessionSandbox(enabledConfig(), SESSION_ID, "u1", "menu-agent", event -> {
        });
        service.ensureSandboxReady(SESSION_ID);

        service.renewSandbox(SESSION_ID);

        verify(sandbox, times(2)).bind(any(SandboxBinding.class));
    }

    @Test
    void renewingLeavesARuntimeThatKeptTheIdentityAlone() {
        var sandbox = readySandbox("sb-1");
        when(sandbox.hubBound()).thenReturn(Boolean.TRUE);
        var service = hubEnabledService(providerAcquiring(sandbox));
        service.createSessionSandbox(enabledConfig(), SESSION_ID, "u1", "menu-agent", event -> {
        });
        service.ensureSandboxReady(SESSION_ID);

        service.renewSandbox(SESSION_ID);

        verify(sandbox, times(1)).bind(any(SandboxBinding.class));
    }

    @Test
    void renewingWithoutTheHubConfiguredTouchesNoRuntime() {
        var sandbox = readySandbox("sb-1");
        var service = new SandboxService(providerAcquiring(sandbox), new SandboxConfig(), "http://core-ai-server:8080",
                new SandboxServiceDependencies(null, null, null, null, null), mock(ScheduledExecutorService.class));
        service.createSessionSandbox(enabledConfig(), SESSION_ID, "u1", "menu-agent", event -> {
        });
        service.ensureSandboxReady(SESSION_ID);

        service.renewSandbox(SESSION_ID);

        verify(sandbox, never()).hubBound();
        verify(sandbox, never()).bind(any(SandboxBinding.class));
    }

    private SandboxProvider providerAcquiring(Sandbox sandbox) {
        var provider = mock(SandboxProvider.class);
        when(provider.acquire(any(), eq(SESSION_ID), eq("u1"))).thenReturn(sandbox);
        return provider;
    }

    private Sandbox readySandbox(String id) {
        var sandbox = mock(Sandbox.class);
        when(sandbox.getId()).thenReturn(id);
        when(sandbox.getStatus()).thenReturn(SandboxStatus.READY);
        return sandbox;
    }

    private SandboxConfig enabledConfig() {
        var config = new SandboxConfig();
        config.enabled = Boolean.TRUE;
        return config;
    }

    private SandboxService hubEnabledService(SandboxProvider provider) {
        var tokens = new SessionTokenService();
        tokens.secret = "session-token-secret-0123456789abcdef".getBytes(StandardCharsets.UTF_8);
        var dependencies = new SandboxServiceDependencies(null, null, null, null, null);
        var service = new SandboxService(provider, new SandboxConfig(), "http://core-ai-server:8080",
                dependencies, mock(ScheduledExecutorService.class));
        service.sessionTokens(tokens);
        return service;
    }

    private SandboxService redisBackedService(String storedSandboxId) {
        var jedis = mock(Jedis.class);
        when(jedis.get("sandbox:" + SESSION_ID)).thenReturn(storedSandboxId);
        var jedisPool = mock(JedisPool.class);
        when(jedisPool.getResource()).thenReturn(jedis);

        var service = new SandboxService(jedisPool, null, null, null);
        service.sessionTokens(mock(SessionTokenService.class));
        return service;
    }

    private SandboxService enabledService(JedisPool jedisPool) {
        var dependencies = new SandboxServiceDependencies(jedisPool, null, null, null, null);
        return new SandboxService(mock(SandboxProvider.class), new SandboxConfig(), "http://core-ai-server:8080",
                dependencies, mock(ScheduledExecutorService.class));
    }
}
