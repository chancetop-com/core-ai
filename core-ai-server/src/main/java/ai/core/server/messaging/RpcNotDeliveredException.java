package ai.core.server.messaging;

/**
 * Thrown when an HTTP RPC definitely did not reach the target (no routable address, connection
 * refused, or endpoint missing), so the caller may safely fall back to the Redis transport.
 *
 * @author stephen
 */
public class RpcNotDeliveredException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public RpcNotDeliveredException(String message) {
        super(message);
    }

    public RpcNotDeliveredException(String message, Throwable cause) {
        super(message, cause);
    }
}
