package ai.core.session;

import ai.core.agent.CancelReason;
import ai.core.agent.CancellationToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

/**
 * Wall-clock guard for a single turn. Max turns only bounds how many model round-trips a turn may take;
 * a tool that never returns leaves the turn running — and the session visibly "stuck" — forever, so the
 * turn is cancelled through the same path a user cancel takes and reports a cancelled turn complete.
 * <p>
 * Kept out of {@link InProcessAgentSession} so that class stays within its file budget; the budget
 * itself is read from {@link SessionRuntimeLimits} every turn, so the host application can change it
 * without a restart.
 *
 * @author stephen
 */
public final class SessionTurnWatchdog {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionTurnWatchdog.class);

    // one daemon watchdog for every session in the process: a turn only needs a single scheduled cancel
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor(r -> {
        var thread = new Thread(r, "turn-deadline-watchdog");
        thread.setDaemon(true);
        return thread;
    });

    /**
     * @return the scheduled deadline; callers cancel it when the turn ends. Never null, so the caller
     *         does not have to branch (an unbounded turn gets an already-cancelled placeholder).
     */
    public static ScheduledFuture<?> schedule(CancellationToken turnToken, String sessionId, BooleanSupplier turnRunning) {
        var timeoutSeconds = SessionRuntimeLimits.current().turnTimeoutSeconds;
        if (timeoutSeconds <= 0) return WATCHDOG.schedule(() -> { }, 0, TimeUnit.MILLISECONDS);
        return WATCHDOG.schedule(() -> {
            if (!turnRunning.getAsBoolean()) return;
            LOGGER.warn("turn exceeded its wall-clock limit, cancelling, sessionId={}, limitSeconds={}", sessionId, timeoutSeconds);
            turnToken.cancel(CancelReason.TIMEOUT);
        }, timeoutSeconds, TimeUnit.SECONDS);
    }

    private SessionTurnWatchdog() {
    }
}
