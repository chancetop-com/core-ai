package ai.core.agent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Unified cancellation token that replaces the ad-hoc {@code volatile boolean cancelled} flag,
 * {@code Thread.interrupt()}, and {@code StreamingCallback.cancelConnection()} scattered across
 * the agent execution pipeline.
 *
 * <h3>Tree propagation</h3>
 * Tokens form a parent-child tree via {@link #createChild()}. Cancelling a parent automatically
 * propagates to all descendants (O(1) cascade). Child cancellation never affects the parent or
 * siblings, except for the explicit permission-gate bubble-up handled by the caller.
 *
 * <h3>Phased cleanup</h3>
 * Cleanup actions are registered in one of four phases, executed in order:
 * <ol>
 *   <li>{@code NOTIFY} — user callbacks first, so listeners can log state before teardown</li>
 *   <li>{@code INTERRUPT} — thread/future interruption</li>
 *   <li>{@code CLOSE} — close connections, streams, and managed resources</li>
 *   <li>{@code ABORT} — last resort: forcibly destroy processes</li>
 * </ol>
 * Phases are an internal implementation detail. Convenience methods
 * ({@link #bindThread}, {@link #bindResource}, {@link #bindProcess}) select the right phase
 * automatically. {@code interrupt()} skips {@code CLOSE} and {@code ABORT} phases.
 *
 * <h3>Per-turn reuse</h3>
 * The root token lives for the lifetime of the agent. Each turn creates a child subtree
 * via {@code rootToken.createChild()}. When the turn ends the child tree becomes unreachable
 * and is garbage collected. The root token stays clean for the next turn.
 *
 * @author lim
 */
public class CancellationToken {

    private static final Logger LOGGER = LoggerFactory.getLogger(CancellationToken.class);

    private enum CancelPhase {
        NOTIFY,
        INTERRUPT,
        CLOSE,
        ABORT
    }

    private static final ScheduledExecutorService SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                var t = new Thread(r, "cancel-deadline");
                t.setDaemon(true);
                return t;
            });

    private final CancellationToken parent;
    private final CopyOnWriteArrayList<CancellationToken> children = new CopyOnWriteArrayList<>();
    private final AtomicBoolean cancelled = new AtomicBoolean(false);
    private volatile CancelReason reason;
    private final ConcurrentHashMap<CancelPhase, CopyOnWriteArrayList<Runnable>> phaseActions = new ConcurrentHashMap<>();

    {
        for (var phase : CancelPhase.values()) {
            phaseActions.put(phase, new CopyOnWriteArrayList<>());
        }
    }

    public static CancellationToken create() {
        return new CancellationToken(null);
    }

    private CancellationToken(CancellationToken parent) {
        this.parent = parent;
    }

    public CancellationToken createChild() {
        var child = new CancellationToken(this);
        children.add(child);
        if (cancelled.get()) {
            child.cancel(reason != null ? reason : CancelReason.USER_CANCELLED);
        }
        return child;
    }

    void disconnect() {
        if (parent != null) {
            parent.children.remove(this);
        }
    }

    public boolean isChild() {
        return parent != null;
    }

    public boolean isCancelled() {
        if (cancelled.get()) return true;
        return parent != null && parent.isCancelled();
    }

    public void cancel() {
        cancel(CancelReason.USER_CANCELLED);
    }

    public void cancel(CancelReason reason) {
        if (!cancelled.compareAndSet(false, true)) {
            LOGGER.debug("cancel skipped (already cancelled), reason={}", getReason());
            return;
        }
        this.reason = reason;

        LOGGER.debug("cancelling token, reason={}, child={}, actionCount={}",
                reason, isChild(),
                phaseActions.values().stream().mapToInt(CopyOnWriteArrayList::size).sum());

        boolean skipCloseAndAbort = reason == CancelReason.NEW_MESSAGE_INTERRUPT;
        if (skipCloseAndAbort) {
            LOGGER.debug("interrupt detected, skipping CLOSE and ABORT phases");
        }

        for (var phase : CancelPhase.values()) {
            if (skipCloseAndAbort && (phase == CancelPhase.CLOSE || phase == CancelPhase.ABORT)) {
                continue;
            }
            var actions = phaseActions.get(phase);
            if (actions.isEmpty()) continue;
            LOGGER.debug("running {} phase, actions={}", phase, actions.size());
            for (var action : actions) {
                try {
                    action.run();
                } catch (Exception e) {
                    LOGGER.debug("cleanup action failed in {} phase: {}", phase, e.getMessage());
                }
            }
        }

        for (var child : children) {
            child.cancel(reason);
        }
    }

    public CancelReason getReason() {
        if (reason != null) return reason;
        if (parent != null) return parent.getReason();
        return null;
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            var r = getReason();
            LOGGER.debug("throwIfCancelled, reason={}, child={}", r, isChild());
            throw r != null ? new CancellationException(r) : new CancellationException();
        }
    }

    public void interrupt() {
        cancel(CancelReason.NEW_MESSAGE_INTERRUPT);
    }

    public boolean isInterrupted() {
        return isCancelled() && getReason() == CancelReason.NEW_MESSAGE_INTERRUPT;
    }

    public void reset() {
        cancelled.set(false);
        reason = null;
        for (var list : phaseActions.values()) {
            list.clear();
        }
    }

    public Runnable onCancel(Runnable action) {
        return onCancel(CancelPhase.NOTIFY, action);
    }

    public Runnable bindThread(Thread thread) {
        return onCancel(CancelPhase.INTERRUPT, thread::interrupt);
    }

    public Runnable bindResource(AutoCloseable resource) {
        return onCancel(CancelPhase.CLOSE, () -> {
            try {
                resource.close();
            } catch (Exception e) {
                // ignore close errors
            }
        });
    }

    public Runnable bindProcess(ProcessHandle process) {
        return onCancel(CancelPhase.ABORT, () -> {
            if (!process.isAlive()) return;
            process.destroy();
            try {
                process.onExit().get(5, TimeUnit.SECONDS);
            } catch (TimeoutException e) {
                process.destroyForcibly();
            } catch (Exception e) {
                // process already exited or errored
            }
        });
    }

    public Runnable cancelAfter(long timeoutMs) {
        var scheduled = new AtomicReference<ScheduledFuture<?>>();
        var future = SCHEDULER.schedule(
                () -> cancel(CancelReason.TIMEOUT), timeoutMs, TimeUnit.MILLISECONDS);
        scheduled.set(future);
        return () -> {
            var sf = scheduled.get();
            if (sf != null) sf.cancel(false);
        };
    }

    public <T> T orCancel(CompletableFuture<T> future, long timeoutMs)
            throws CancellationException, TimeoutException, ExecutionException {
        throwIfCancelled();
        var deregister = onCancel(CancelPhase.INTERRUPT, () -> future.cancel(true));
        try {
            var result = future.get(timeoutMs, TimeUnit.MILLISECONDS);
            deregister.run();
            return result;
        } catch (CancellationException e) {
            deregister.run();
            throw e;
        } catch (InterruptedException e) {
            deregister.run();
            Thread.currentThread().interrupt();
            var r = getReason();
            throw r != null ? new CancellationException(r) : new CancellationException();
        } catch (java.util.concurrent.CancellationException e) {
            deregister.run();
            var r = getReason();
            throw r != null ? new CancellationException(r) : new CancellationException();
        } catch (TimeoutException | ExecutionException e) {
            deregister.run();
            throw e;
        }
    }

    private Runnable onCancel(CancelPhase phase, Runnable action) {
        var list = phaseActions.get(phase);
        list.add(action);
        if (isCancelled()) {
            boolean skipCloseAndAbort = getReason() == CancelReason.NEW_MESSAGE_INTERRUPT;
            if (!skipCloseAndAbort || (phase != CancelPhase.CLOSE && phase != CancelPhase.ABORT)) {
                try {
                    action.run();
                } catch (Exception e) {
                    // ignore
                }
            }
        }
        return () -> list.remove(action);
    }
}
