package ai.core.agent;

import java.io.Serial;

/**
 * Thrown by {@link CancellationToken#throwIfCancelled()} when the token has been cancelled.
 * Unchecked so it propagates through existing call chains without signature changes.
 *
 * @author lim
 */
public class CancellationException extends RuntimeException {
    @Serial
    private static final long serialVersionUID = 6492837150602938475L;

    private final CancelReason reason;

    public CancellationException() {
        super("operation cancelled");
        this.reason = null;
    }

    public CancellationException(CancelReason reason) {
        super("operation cancelled: " + reason.name().toLowerCase());
        this.reason = reason;
    }

    public CancelReason getReason() {
        return reason;
    }
}
