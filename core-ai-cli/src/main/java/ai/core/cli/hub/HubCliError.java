package ai.core.cli.hub;

/**
 * Fatal, user-facing error raised by hub command plumbing (missing credentials,
 * missing server, unreadable args file). Carries the exit code the command must return.
 *
 * @author stephen
 */
public class HubCliError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public final int exitCode;

    public HubCliError(int exitCode, String message) {
        super(message);
        this.exitCode = exitCode;
    }

    public HubCliError(int exitCode, String message, Throwable cause) {
        super(message, cause);
        this.exitCode = exitCode;
    }
}
