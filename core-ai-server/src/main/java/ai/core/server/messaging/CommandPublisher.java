package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

/**
 * @author stephen
 */
public class CommandPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandPublisher.class);

    private final JedisPool jedisPool;

    public CommandPublisher(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public void publish(SessionCommand command) {
        try (var jedis = jedisPool.getResource()) {
            var map = command.toStreamMap();
            var messageId = jedis.xadd(SessionCommand.streamKey(), StreamEntryID.NEW_ENTRY, map);
            LOGGER.debug("command published: type={}, sessionId={}, messageId={}", command.type(), command.sessionId(), messageId);
        }
    }
}
