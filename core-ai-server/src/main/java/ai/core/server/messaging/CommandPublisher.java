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
    private final SessionOwnershipRegistry ownershipRegistry;

    public CommandPublisher(JedisPool jedisPool, SessionOwnershipRegistry ownershipRegistry) {
        this.jedisPool = jedisPool;
        this.ownershipRegistry = ownershipRegistry;
    }

    public void publish(SessionCommand command) {
        var targetStream = resolveTargetStream(command.sessionId());
        try (var jedis = jedisPool.getResource()) {
            var messageId = jedis.xadd(targetStream, StreamEntryID.NEW_ENTRY, command.toStreamMap());
            LOGGER.debug("command published to stream={}, type={}, sessionId={}, messageId={}",
                    targetStream, command.type(), command.sessionId(), messageId);
        }
    }

    private String resolveTargetStream(String sessionId) {
        var owner = ownershipRegistry.getOwner(sessionId);
        if (owner != null) {
            return SessionCommand.podStreamKey(owner);
        }
        return SessionCommand.UNOWNED_STREAM;
    }
}
