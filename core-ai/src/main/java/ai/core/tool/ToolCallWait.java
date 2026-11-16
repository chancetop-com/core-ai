package ai.core.tool;

import ai.core.agent.ExecutionContext;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Waits for one running tool call, but stops waiting as soon as a user message is queued for the
 * turn. A steered message is injected only before the next model call, so a tool that runs for
 * minutes would otherwise hold the user's message back for its whole runtime.
 *
 * @author stephen
 */
final class ToolCallWait {
    private static final Logger LOGGER = LoggerFactory.getLogger(ToolCallWait.class);
    // how often the wait re-checks for a user message waiting to be injected
    private static final long USER_INPUT_POLL_MILLIS = 100;
    private static final String CANCELLED_BY_USER_MESSAGE =
            "Tool call cancelled: the user sent a new message while this tool was running. "
                    + "The cancelled call produced no result. Do not retry it; read the latest user message instead.";

    /**
     * @param timeoutMs overall timeout, or 0 for a wait without one (the sandbox bounds the command itself)
     */
    static Outcome await(CompletableFuture<ToolCallResult> future, long timeoutMs, String toolName,
                         ExecutionContext context, AtomicReference<Thread> toolThread)
            throws ExecutionException, InterruptedException {
        var bounded = timeoutMs > 0;
        var deadline = bounded ? System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs) : 0L;
        var poll = TimeUnit.MILLISECONDS.toNanos(USER_INPUT_POLL_MILLIS);
        while (true) {
            if (context.hasWaitingUserInput() && future.cancel(true)) {
                interrupt(toolThread);
                LOGGER.info("tool call cancelled by a new user message, tool={}", toolName);
                return new Outcome(ToolCallResult.failed(CANCELLED_BY_USER_MESSAGE), false, true);
            }
            var slice = bounded ? Math.min(poll, deadline - System.nanoTime()) : poll;
            if (slice > 0) {
                var result = resultWithin(future, slice);
                if (result != null) return new Outcome(result, false, false);
            } else if (future.isDone() && !future.isCancelled()) {
                return new Outcome(future.get(), false, false);
            } else {
                future.cancel(true);
                interrupt(toolThread);
                LOGGER.warn("tool {} execution timed out after {}ms", toolName, timeoutMs);
                return new Outcome(ToolCallResult.failed(
                        Strings.format("tool call timed out after {}ms: {}", timeoutMs, toolName)), true, false);
            }
        }
    }

    /** The tool result, or null when the slice elapsed before the call finished. */
    private static ToolCallResult resultWithin(CompletableFuture<ToolCallResult> future, long slice)
            throws ExecutionException, InterruptedException {
        try {
            return future.get(slice, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            return null;
        }
    }

    private static void interrupt(AtomicReference<Thread> toolThread) {
        var thread = toolThread.get();
        if (thread != null) thread.interrupt();
    }

    private ToolCallWait() {
    }

    record Outcome(ToolCallResult result, boolean timedOut, boolean cancelled) {
    }
}
