package ai.core.server.messaging;

import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TurnStateRegistryTest {
    private static final String TURN_KEY = "session:turn:s-1";

    private JedisPool jedisPool;
    private Jedis jedis;
    private TurnStateRegistry registry;

    @BeforeEach
    void setUp() {
        jedisPool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        registry = new TurnStateRegistry(jedisPool);
    }

    @Test
    void markRunningWritesTurnKeyWithTtl() {
        registry.markRunning("s-1");

        verify(jedis).set(eq(TURN_KEY), anyString(), any(SetParams.class));
    }

    @Test
    void clearDeletesTurnKey() {
        registry.markRunning("s-1");
        registry.clear("s-1");

        verify(jedis).del(TURN_KEY);
    }

    @Test
    void renewAllRewritesKeysOfMarkedTurnsOnly() {
        registry.markRunning("s-1");
        registry.clear("s-1");
        registry.markRunning("s-2");

        registry.renewAll();

        // s-1 was written once by markRunning, not renewed after clear
        verify(jedis, times(1)).set(eq(TURN_KEY), anyString(), any(SetParams.class));
        verify(jedis, times(2)).set(eq("session:turn:s-2"), anyString(), any(SetParams.class));
    }

    @Test
    void renewAllClearsKeysWhoseTurnIsNoLongerExecuting() {
        registry.markRunning("s-1", () -> false);

        registry.renewAll();

        // the dead turn's key is deleted instead of renewed, so the TTL safety net can do its job
        verify(jedis).del(TURN_KEY);
        verify(jedis, times(1)).set(eq(TURN_KEY), anyString(), any(SetParams.class));

        registry.renewAll();
        verify(jedis, times(1)).del(TURN_KEY);
    }

    @Test
    void renewAllKeepsRenewingWhileTheTurnIsStillExecuting() {
        registry.markRunning("s-1", () -> true);

        registry.renewAll();
        registry.renewAll();

        verify(jedis, times(3)).set(eq(TURN_KEY), anyString(), any(SetParams.class));
        verify(jedis, never()).del(TURN_KEY);
    }

    @Test
    void livenessReturnsRunningWhenKeyExists() {
        when(jedis.get(TURN_KEY)).thenReturn("running");

        assertEquals(TurnStateRegistry.TurnLiveness.RUNNING, registry.liveness("s-1"));
    }

    @Test
    void livenessReturnsNotRunningWhenKeyAbsent() {
        when(jedis.get(TURN_KEY)).thenReturn(null);

        assertEquals(TurnStateRegistry.TurnLiveness.NOT_RUNNING, registry.liveness("s-1"));
    }

    @Test
    void livenessReturnsUnknownWhenRedisUnavailable() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("connection refused"));

        assertEquals(TurnStateRegistry.TurnLiveness.UNKNOWN, registry.liveness("s-1"));
    }

    @Test
    void markAndClearSwallowRedisFailures() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("connection refused"));

        assertDoesNotThrow(() -> registry.markRunning("s-1"));
        assertDoesNotThrow(() -> registry.clear("s-1"));
    }

    @Test
    void listenerMarksRunningOnRunningStatusChange() {
        registry.listener("s-1").onStatusChange(StatusChangeEvent.of("s-1", SessionStatus.RUNNING));

        verify(jedis).set(eq(TURN_KEY), anyString(), any(SetParams.class));
    }

    @Test
    void listenerClearsOnIdleAndErrorStatusChange() {
        var listener = registry.listener("s-1");
        listener.onStatusChange(StatusChangeEvent.of("s-1", SessionStatus.IDLE));
        listener.onStatusChange(StatusChangeEvent.of("s-1", SessionStatus.ERROR));

        verify(jedis, times(2)).del(TURN_KEY);
    }

    @Test
    void listenerClearsOnTurnComplete() {
        registry.listener("s-1").onTurnComplete(TurnCompleteEvent.of("s-1", "done"));

        verify(jedis).del(TURN_KEY);
    }

    @Test
    void stopClearsAllTrackedTurns() {
        registry.markRunning("s-1");
        registry.markRunning("s-2");

        registry.stop();

        verify(jedis).del(TURN_KEY);
        verify(jedis).del("session:turn:s-2");
        registry.renewAll();
        // only the initial markRunning writes — nothing is renewed after stop
        verify(jedis, times(1)).set(eq(TURN_KEY), anyString(), any(SetParams.class));
    }
}
