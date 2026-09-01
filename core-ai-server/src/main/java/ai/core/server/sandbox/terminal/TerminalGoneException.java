package ai.core.server.sandbox.terminal;

import java.io.Serial;

/**
 * Thrown when the sandbox runtime reports that a terminal session no longer
 * exists or has already exited (runtime HTTP 410 or 404).
 *
 * @author xander
 */
public class TerminalGoneException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public TerminalGoneException(String message) {
        super(message);
    }
}
