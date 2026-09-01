package ai.core.server.sandbox.terminal;

import java.io.Serial;

/**
 * Thrown when the sandbox runtime cannot be reached (connect failure or
 * timeout), returns a server error (5xx), or returns a malformed body for an
 * otherwise successful response.
 *
 * @author xander
 */
public class TerminalRuntimeUnavailableException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 1L;

    public TerminalRuntimeUnavailableException(String message) {
        super(message);
    }

    public TerminalRuntimeUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
