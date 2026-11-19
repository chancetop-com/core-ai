package ai.core.server.messaging;

import ai.core.server.sandbox.SandboxService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author stephen
 */
public class CommandPublisher {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandPublisher.class);

    private final SessionOwnershipRegistry ownershipRegistry;
    private final SandboxService sandboxService;
    private final InProcessCommandHandler commandHandler;
    private final boolean claimLocally;
    private final PgCommandQueue pgQueue;

    public CommandPublisher(SessionOwnershipRegistry ownershipRegistry, SandboxService sandboxService,
                            InProcessCommandHandler commandHandler, boolean claimLocally, PgCommandQueue pgQueue) {
        this.ownershipRegistry = ownershipRegistry;
        this.sandboxService = sandboxService;
        this.commandHandler = commandHandler;
        this.claimLocally = claimLocally;
        this.pgQueue = pgQueue;
    }

    public void publish(SessionCommand command) {
        if (claimAndProcessLocally(command)) return;
        publishPostgres(command);
    }

    private void publishPostgres(SessionCommand command) {
        var owner = ownershipRegistry.getOwner(command.sessionId());
        try {
            pgQueue.publish(command, owner);
            LOGGER.info("[PUBLISH] command published via PostgreSQL, type={}, sessionId={}, targetPod={}",
                    command.type(), command.sessionId(), owner);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to publish command to PostgreSQL, type={}, sessionId={}",
                    command.type(), command.sessionId(), e);
            if (!processLocally(command)) {
                throw new RuntimeException("failed to deliver command: PostgreSQL unavailable and session not owned locally, sessionId="
                        + command.sessionId(), e);
            }
        }
    }

    /**
     * Locality first: an unowned session is claimed by the server that received the request. Its SSE channel and
     * its sandbox live here, and another process on the same database would otherwise race for the command and run
     * the turn where the user cannot see it. Only when the claim is lost — or this process has no handler — does the
     * command travel.
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
}
