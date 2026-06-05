package ai.core.server.messaging;

import ai.core.server.sandbox.SandboxService;
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
    private final SandboxService sandboxService;
    private InProcessCommandHandler commandHandler;

    public CommandPublisher(JedisPool jedisPool, SessionOwnershipRegistry ownershipRegistry, SandboxService sandboxService) {
        this.jedisPool = jedisPool;
        this.ownershipRegistry = ownershipRegistry;
        this.sandboxService = sandboxService;
    }

    public void setCommandHandler(InProcessCommandHandler commandHandler) {
        this.commandHandler = commandHandler;
    }

    public void publish(SessionCommand command) {
        var targetStream = resolveTargetStream(command.sessionId());
        LOGGER.info("[PUBLISH] publishing command: type={}, sessionId={}, targetStream={}",
                command.type(), command.sessionId(), targetStream);
        try (var jedis = jedisPool.getResource()) {
            var messageId = jedis.xadd(targetStream, StreamEntryID.NEW_ENTRY, command.toStreamMap());
            LOGGER.info("[PUBLISH] command published via Redis, messageId={}", messageId);
        } catch (Exception e) {
            LOGGER.warn("failed to publish command to Redis, type={}, sessionId={}, falling back to local processing",
                    command.type(), command.sessionId(), e);
            if (!processLocally(command)) {
                throw new RuntimeException("failed to deliver command: Redis unavailable and session not owned locally, sessionId=" + command.sessionId(), e);
            }
        }
    }

    private boolean processLocally(SessionCommand command) {
        if (commandHandler == null) return false;
        if (!sandboxService.hasSandbox(command.sessionId())) {
            LOGGER.info("session {} not owned locally, cannot process command locally", command.sessionId());
            return false;
        }
        LOGGER.info("processing command locally, type={}, sessionId={}", command.type(), command.sessionId());
        try {
            commandHandler.handle(command);
            return true;
        } catch (Exception e) {
            LOGGER.error("failed to process command locally, sessionId={}", command.sessionId(), e);
            return false;
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
