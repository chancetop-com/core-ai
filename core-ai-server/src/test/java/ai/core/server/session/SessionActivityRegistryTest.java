package ai.core.server.session;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionActivityRegistryTest {
    private static final String KEY = "session-activity:s1";

    private JedisPool jedisPool;
    private Jedis jedis;
    private SessionActivityRegistry registry;

    @BeforeEach
    void setUp() {
        jedisPool = mock(JedisPool.class);
        jedis = mock(Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        registry = new SessionActivityRegistry(jedisPool);
    }

    @Test
    void absentSessionReportsZeroInDegradedMode() {
        var degraded = new SessionActivityRegistry(null);

        assertEquals(0L, degraded.lastActivity("missing"));
    }

    @Test
    void degradedModeTouchIsNoOp() {
        var degraded = new SessionActivityRegistry(null);

        assertDoesNotThrow(() -> degraded.touch("s1"));
    }

    @Test
    void touchWritesKeyWithTtl() {
        registry.touch("s1");

        verify(jedis).set(eq(KEY), anyString(), any(SetParams.class));
    }

    @Test
    void touchThenReadRoundTrip() {
        var stored = new AtomicReference<String>();
        when(jedis.set(eq(KEY), anyString(), any(SetParams.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(1));
            return "OK";
        });
        when(jedis.get(KEY)).thenAnswer(invocation -> stored.get());
        long before = System.currentTimeMillis();

        registry.touch("s1");

        assertTrue(registry.lastActivity("s1") >= before);
    }

    @Test
    void lastActivityReturnsZeroWhenKeyAbsent() {
        when(jedis.get(KEY)).thenReturn(null);

        assertEquals(0L, registry.lastActivity("s1"));
    }

    @Test
    void lastActivityReturnsZeroWhenRedisUnavailable() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("connection refused"));

        assertEquals(0L, registry.lastActivity("s1"));
    }

    @Test
    void touchSwallowsRedisFailure() {
        when(jedisPool.getResource()).thenThrow(new RuntimeException("connection refused"));

        assertDoesNotThrow(() -> registry.touch("s1"));
    }
}
