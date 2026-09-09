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
    private final InProcessCommandHandler commandHandler;
    private final boolean claimLocally;

    public CommandPublisher(JedisPool jedisPool, SessionOwnershipRegistry ownershipRegistry, SandboxService sandboxService,
                            InProcessCommandHandler commandHandler) {
        this(jedisPool, ownershipRegistry, sandboxService, commandHandler, true);
    }

    /** @param claimLocally sys.session.claimLocally — unowned sessions run on the receiving instance (default) or go through the shared stream */
    public CommandPublisher(JedisPool jedisPool, SessionOwnershipRegistry ownershipRegistry, SandboxService sandboxService,
                            InProcessCommandHandler commandHandler, boolean claimLocally) {
        this.jedisPool = jedisPool;
        this.ownershipRegistry = ownershipRegistry;
        this.sandboxService = sandboxService;
        this.commandHandler = commandHandler;
        this.claimLocally = claimLocally;
    }

    public void publish(SessionCommand command) {
        if (claimAndProcessLocally(command)) return;
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

    /**
     * Locality first: an unowned session is claimed by the server that received the request. Its SSE channel and
     * its sandbox live here, and any other process on the same Redis (a compose stack next to an IDE run, a
     * replica with a different sandbox provider) would otherwise race for the command and run the turn where the
     * user cannot see it. Only when the claim is lost — or this process has no handler — does the command travel.
     */
    private boolean claimAndProcessLocally(SessionCommand command) {
        if (!claimLocally || commandHandler == null || command.sessionId() == null) return false;
        if (ownershipRegistry.getOwner(command.sessionId()) != null) return false;
        if (!ownershipRegistry.claim(command.sessionId())) return false;
        LOGGER.info("[PUBLISH] claimed unowned session locally, processing in-process: type={}, sessionId={}", command.type(), command.sessionId());
        commandHandler.handle(command);
        return true;
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
