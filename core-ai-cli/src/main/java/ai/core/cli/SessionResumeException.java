package ai.core.cli;

import java.io.Serial;

/**
 * A {@code --resume <session-id>} request that cannot be satisfied. The message is user facing.
 *
 * @author stephen
 */
public class SessionResumeException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public SessionResumeException(String message) {
        super(message);
    }

    public SessionResumeException(String message, Throwable cause) {
        super(message, cause);
    }
}
