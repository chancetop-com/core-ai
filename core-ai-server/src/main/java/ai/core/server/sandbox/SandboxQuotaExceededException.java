package ai.core.server.sandbox;

/**
 * Refused by the sandbox budgets (pod total, per user count, per user memory). Surfaces to the agent
 * as a failed tool call with the reason, so it can degrade or tell the user, instead of looking like
 * a broken sandbox.
 *
 * @author stephen
 */
public class SandboxQuotaExceededException extends RuntimeException {
    private static final long serialVersionUID = 3390382484722289817L;

    public SandboxQuotaExceededException(String message) {
        super(message);
    }
}
