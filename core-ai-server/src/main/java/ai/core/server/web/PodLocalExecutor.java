package ai.core.server.web;

import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionCommand;
import ai.core.server.messaging.SessionOwnershipRegistry;
import core.framework.inject.Inject;

import java.time.Duration;

/**
 * Utility for executing operations on the Pod that owns a session.
 * <p>
 * If the current Pod owns the session, the operation runs locally.
 * If not, the request is forwarded to the owner Pod via RPC (Redis Streams).
 * <p>
 * Usage in a controller method:
 * <pre>{@code
 * // Option A: Execute locally or forward via RPC
 * return podLocalExecutor.execute(
 *     sessionId,
 *     () -> handleLocally(request),              // Lambda that runs on the owner Pod
 *     buildRpcCommand(sessionId, payload),        // SessionCommand built by the caller
 *     MyResponse.class                           // Expected response type
 * );
 *
 * // Option B: Just check ownership (no forwarding)
 * if (!podLocalExecutor.isOwner(sessionId)) {
 *     throw new ServiceUnavailableException("session owned by " + podLocalExecutor.getOwner(sessionId));
 * }
 * }</pre>
 * <p>
 * When using Option A, the caller must:
 * <ol>
 *   <li>Add a new value to {@code CommandType} enum</li>
 *   <li>Add a handler in {@code InProcessCommandHandler} for the new command type</li>
 *   <li>Build a {@link SessionCommand} with the new command type and a unique requestId</li>
 * </ol>
 *
 * @author stephen
 */
public class PodLocalExecutor {
    public static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(30);
    public static final Duration LONG_TIMEOUT = Duration.ofSeconds(60);

    @Inject
    SessionOwnershipRegistry ownershipRegistry;

    @Inject
    RpcClient rpcClient;

    /**
     * Check if the current Pod owns the session.
     * Returns true when ownership registry is unavailable (CLI / single-Pod mode).
     */
    public boolean isOwner(String sessionId) {
        if (ownershipRegistry == null) return true;
        return ownershipRegistry.isOwner(sessionId);
    }

    /**
     * Get the hostname of the Pod that owns the session, or null if none.
     */
    public String getOwner(String sessionId) {
        if (ownershipRegistry == null) return null;
        return ownershipRegistry.getOwner(sessionId);
    }

    /**
     * Get the hostname of the current Pod.
     */
    public String getHostname() {
        if (ownershipRegistry == null) return "local";
        return ownershipRegistry.getHostname();
    }

    /**
     * Execute an operation on the owner Pod.
     * <p>
     * If the current Pod is the owner, the local operation is executed directly.
     * Otherwise, the request is forwarded via RPC.
     *
     * @param sessionId    the session ID
     * @param localOp      the operation to execute locally (when current Pod is owner)
     * @param command      the RPC command to forward (when current Pod is not owner)
     * @param responseType the expected response type
     * @param <T>          the response type
     * @return the operation result
     */
    public <T> T execute(String sessionId, java.util.function.Supplier<T> localOp,
                         SessionCommand command, Class<T> responseType) {
        return execute(sessionId, localOp, command, responseType, DEFAULT_TIMEOUT);
    }

    /**
     * Execute an operation on the owner Pod with a custom timeout.
     *
     * @param sessionId    the session ID
     * @param localOp      the operation to execute locally (when current Pod is owner)
     * @param command      the RPC command to forward (when current Pod is not owner)
     * @param responseType the expected response type
     * @param timeout      the RPC timeout duration
     * @param <T>          the response type
     * @return the operation result
     */
    public <T> T execute(String sessionId, java.util.function.Supplier<T> localOp,
                         SessionCommand command, Class<T> responseType, Duration timeout) {
        if (isOwner(sessionId)) {
            return localOp.get();
        }
        return rpcClient.call(command, responseType, timeout);
    }
}
