package ai.core.session;

import java.util.function.Supplier;

/**
 * Session-level budgets supplied by the server, so the framework module does not have to know where
 * configuration lives. Values are re-applied per user message, which is what makes an admin change
 * take effect on the next turn instead of the next restart.
 *
 * @author stephen
 */
public class SessionRuntimeLimits {
    /** Nothing is capped: the shipped behaviour for embedders that do not supply limits. */
    public static final SessionRuntimeLimits DEFAULT = new SessionRuntimeLimits(0, 0);

    // Set once by the host application (the server reads the live system settings); an embedder that
    // never configures it — the CLI, tests — keeps the unlimited default.
    private static volatile Supplier<SessionRuntimeLimits> source;

    public static void configure(Supplier<SessionRuntimeLimits> value) {
        source = value;
    }

    public static SessionRuntimeLimits current() {
        var supplier = source;
        if (supplier == null) return DEFAULT;
        try {
            var limits = supplier.get();
            return limits == null ? DEFAULT : limits;
        } catch (Exception e) {
            return DEFAULT;
        }
    }

    /** Messages that may wait behind a running turn before further input is refused. 0 = unlimited. */
    public final int maxQueuedMessages;

    /** Wall-clock budget of one turn in seconds, 0 = no deadline (agent cancellation still works). */
    public final int turnTimeoutSeconds;

    public SessionRuntimeLimits(int maxQueuedMessages, int turnTimeoutSeconds) {
        this.maxQueuedMessages = maxQueuedMessages;
        this.turnTimeoutSeconds = turnTimeoutSeconds;
    }
}
