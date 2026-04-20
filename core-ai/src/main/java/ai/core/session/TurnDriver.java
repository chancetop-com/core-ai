package ai.core.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.function.Consumer;

/**
 * Event-driven turn scheduler that runs in a daemon virtual thread.
 * Blocks on an empty queue (zero CPU), wakes up when commands arrive,
 * and executes them synchronously via the provided handler.
 */
public class TurnDriver {

    private final Logger logger = LoggerFactory.getLogger(TurnDriver.class);

    private final SessionCommandQueue commandQueue;
    private final Consumer<SessionCommandQueue.CommandBatch> commandHandler;
    private volatile boolean running = true;
    private final Thread driverThread;

    public TurnDriver(SessionCommandQueue commandQueue, Consumer<SessionCommandQueue.CommandBatch> commandHandler) {
        this.commandQueue = commandQueue;
        this.commandHandler = commandHandler;
        this.driverThread = Thread.ofVirtual().name("turn-driver").start(this::driveLoop);
    }

    private void driveLoop() {
        while (running) {
            try {
                commandQueue.awaitNonEmpty();
                var batch = commandQueue.drainSameMode();
                if (batch.isEmpty()) continue;
                commandHandler.accept(batch);
                // Clear interrupt flag left by cancelTurn() so the next iteration works
                //noinspection ResultOfMethodCallIgnored
                Thread.interrupted();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("unexpected error in TurnDriver", e);
            }
        }
    }

    public void shutdown() {
        running = false;
        driverThread.interrupt();
    }
}
