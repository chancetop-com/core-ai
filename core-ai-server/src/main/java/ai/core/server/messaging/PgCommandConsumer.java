package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Polls the PostgreSQL command queue for this pod. Redis is still consumed by the legacy
 * {@link CommandConsumer} during the mixed-version window.
 *
 * @author stephen
 */
public class PgCommandConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(PgCommandConsumer.class);
    private static final int CLAIM_BATCH = 16;
    private static final long IDLE_SLEEP_MS = 200;
    private static final long UNOWNED_RETRY_SECONDS = 5;

    private final PgCommandQueue queue;
    private final InProcessCommandHandler commandHandler;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final String podId;
    private volatile boolean running = true;
    private Thread thread;

    public PgCommandConsumer(PgCommandQueue queue, InProcessCommandHandler commandHandler,
                             SessionOwnershipRegistry ownershipRegistry) {
        this.queue = queue;
        this.commandHandler = commandHandler;
        this.ownershipRegistry = ownershipRegistry;
        this.podId = ownershipRegistry.getHostname();
    }

    public void start() {
        thread = Thread.ofVirtual().name("pg-command-consumer-" + podId).start(this::loop);
        LOGGER.info("PgCommandConsumer started, podId={}", podId);
    }

    public void stop() {
        running = false;
        if (thread != null) thread.interrupt();
        try {
            queue.releaseClaimedBy(podId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to release claimed commands, podId={}", podId, e);
        }
    }

    private void loop() {
        while (running) {
            try {
                var claimed = queue.claim(podId, CLAIM_BATCH);
                if (claimed.isEmpty()) {
                    sleep();
                    continue;
                }
                for (var item : claimed) {
                    if (!running) return;
                    process(item);
                }
            } catch (Throwable e) {
                if (running) {
                    LOGGER.warn("pg command consumer error, podId={}", podId, e);
                    sleep();
                }
            }
        }
    }

    private void process(PgCommandQueue.ClaimedCommand item) {
        var command = item.command();
        if (item.targetPod() == null && !ownershipRegistry.claim(command.sessionId())) {
            queue.retryLater(item.id(), podId, item.attempts(), UNOWNED_RETRY_SECONDS);
            return;
        }
        try {
            commandHandler.handle(command);
            queue.complete(item.id(), podId);
        } catch (Throwable e) {
            LOGGER.error("pg command failed, type={}, sessionId={}, attempts={}",
                    command.type(), command.sessionId(), item.attempts(), e);
            queue.fail(item.id(), podId, item.attempts(), e);
        }
    }

    private void sleep() {
        try {
            Thread.sleep(IDLE_SLEEP_MS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
