package ai.core.server;

import core.framework.mongo.Mongo;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StartupDependencyCheckTest {
    @Test
    void verifyPassesWhenDependenciesRespond() {
        var jedisPool = mock(JedisPool.class);
        var jedis = mock(Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.ping()).thenReturn("PONG");
        var mongo = mock(Mongo.class);
        when(mongo.runCommand(any())).thenReturn(new Document("ok", 1.0));

        assertDoesNotThrow(() -> new StartupDependencyCheck(jedisPool, mongo).verify());
    }

    @Test
    void verifyFailsWhenRedisIsUnavailable() {
        var jedisPool = mock(JedisPool.class);
        when(jedisPool.getResource()).thenThrow(new RuntimeException("redis down"));
        var mongo = mock(Mongo.class);

        assertThrows(Error.class, () -> new StartupDependencyCheck(jedisPool, mongo).verify());
    }

    @Test
    void verifyFailsWhenMongoIsUnavailable() {
        var jedisPool = mock(JedisPool.class);
        var jedis = mock(Jedis.class);
        when(jedisPool.getResource()).thenReturn(jedis);
        when(jedis.ping()).thenReturn("PONG");
        var mongo = mock(Mongo.class);
        when(mongo.runCommand(any())).thenThrow(new RuntimeException("mongo down"));

        assertThrows(Error.class, () -> new StartupDependencyCheck(jedisPool, mongo).verify());
    }
}
