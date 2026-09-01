package ai.core.server.sandbox.terminal;

import java.io.Serial;

/**
 * Thrown when the sandbox runtime rejects a terminal create request because
 * another client already holds an active terminal session (runtime HTTP 429).
 *
 * @author xander
 */
public class TerminalBusyException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public TerminalBusyException(String message) {
        super(message);
    }
}
