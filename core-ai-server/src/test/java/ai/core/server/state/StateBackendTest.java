package ai.core.server.state;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class StateBackendTest {
    @Test
    void readsRedis() {
        assertEquals(StateBackend.REDIS, StateBackend.of("redis", "pg"));
    }

    @Test
    void postgresRequiresPgStoreMode() {
        assertEquals(StateBackend.POSTGRES, StateBackend.of("postgres", "pg"));
        assertThrows(IllegalArgumentException.class, () -> StateBackend.of("postgres", "mongo"));
    }

    @Test
    void rejectsUnknownValue() {
        assertThrows(IllegalArgumentException.class, () -> StateBackend.of("memory", "pg"));
    }
}
