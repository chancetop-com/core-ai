package ai.core.agent.subagent;

import java.io.Serial;

/**
 * A dormant thread could not be rebuilt — its type no longer exists or its persisted context is
 * missing or unreadable. The thread is kept dormant so nothing is silently lost.
 *
 * @author stephen
 */
public class SubagentThreadUnavailableException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 7384920174620193847L;

    public SubagentThreadUnavailableException(String message) {
        super(message);
    }

    public SubagentThreadUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
