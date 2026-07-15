package ai.core.session;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Event-driven turn scheduler that runs in a daemon virtual thread.
 * Blocks on an empty queue (zero CPU), wakes up when commands arrive,
 * and executes them synchronously via the provided handler.
 */
public class TurnDriver {
    private static final long IDLE_RENEW_INTERVAL_SECONDS = 25;
    private static final long PROCESSING_HEARTBEAT_INTERVAL_MS = 10_000;

    private final Logger logger = LoggerFactory.getLogger(TurnDriver.class);

    private final SessionCommandQueue commandQueue;
    private final Consumer<SessionCommandQueue.CommandBatch> commandHandler;
    private volatile boolean running = true;
    private final Thread driverThread;
    private volatile Runnable onIdle;
    private volatile ScheduledExecutorService heartbeatScheduler;

    public TurnDriver(SessionCommandQueue commandQueue, Consumer<SessionCommandQueue.CommandBatch> commandHandler) {
        this.commandQueue = commandQueue;
        this.commandHandler = commandHandler;
        this.driverThread = Thread.ofVirtual().name("turn-driver").start(this::driveLoop);
    }

    private void driveLoop() {
        logger.info("TurnDriver started, thread={}", Thread.currentThread().getName());
        while (running) {
            try {
                var hasCommand = commandQueue.awaitNonEmpty(IDLE_RENEW_INTERVAL_SECONDS, TimeUnit.SECONDS);
                if (!hasCommand) {
                    // Idle timeout — renew ownership before re-entering wait
                    renewOwnership();
                    continue;
                }
                processBatch();
                // Clear interrupt flag left by cancelTurn() so the next iteration works
                //noinspection ResultOfMethodCallIgnored
                Thread.interrupted();
            } catch (InterruptedException e) {
                logger.info("TurnDriver interrupted, running={}, exiting driveLoop", running);
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("unexpected error in TurnDriver", e);
            }
        }
        logger.info("TurnDriver exited driveLoop, thread={}", Thread.currentThread().getName());
    }

    private void processBatch() {
        var batch = commandQueue.drainSameMode();
        if (batch.isEmpty()) return;
        renewOwnership();
        startHeartbeat();
        try {
            commandHandler.accept(batch);
        } finally {
            stopHeartbeat();
        }
    }

    private void renewOwnership() {
        var callback = onIdle;
        if (callback != null) {
            try {
                callback.run();
            } catch (Exception e) {
                logger.warn("onIdle callback threw", e);
            }
        }
    }

    private void startHeartbeat() {
        heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> Thread.ofVirtual().name("turn-heartbeat").unstarted(r));
        heartbeatScheduler.scheduleAtFixedRate(this::renewOwnership,
                PROCESSING_HEARTBEAT_INTERVAL_MS, PROCESSING_HEARTBEAT_INTERVAL_MS, TimeUnit.MILLISECONDS);
    }

    private void stopHeartbeat() {
        var scheduler = heartbeatScheduler;
        heartbeatScheduler = null;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
    }

    public void setOnIdle(Runnable onIdle) {
        this.onIdle = onIdle;
    }

    public void shutdown() {
        logger.info("TurnDriver.shutdown called, running={}, thread={}", running, driverThread.getName());
        running = false;
        driverThread.interrupt();
        var scheduler = heartbeatScheduler;
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        logger.info("TurnDriver.shutdown completed");
    }
}
