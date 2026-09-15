package ai.core.server.sandbox;

import ai.core.sandbox.SandboxConfig;
import ai.core.sandbox.SandboxProvider;
import ai.core.server.sandboxhub.SessionTokenService;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import java.util.concurrent.ScheduledExecutorService;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
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
