package ai.core.server.messaging;

import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.time.Duration;

/**
 * @author stephen
 */
public record JedisConfig(String host, int port) {

    public JedisPool createJedisPool() {
        var config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(2);
        config.setMaxWait(Duration.ofSeconds(5));
        config.setTestOnBorrow(true);
        config.setTestOnReturn(true);
        return new JedisPool(config, host, port, 5000, null, 0);
    }
}
