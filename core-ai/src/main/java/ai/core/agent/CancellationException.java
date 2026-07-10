package ai.core.agent;

import java.io.Serial;
import java.util.Locale;

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

    public CancellationException(Throwable cause) {
        super("operation cancelled", cause);
        this.reason = null;
    }

    public CancellationException(CancelReason reason) {
        super("operation cancelled: " + reason.name().toLowerCase(Locale.ROOT));
        this.reason = reason;
    }

    public CancellationException(CancelReason reason, Throwable cause) {
        super("operation cancelled: " + reason.name().toLowerCase(Locale.ROOT), cause);
        this.reason = reason;
    }

    public CancelReason getReason() {
        return reason;
    }
}
