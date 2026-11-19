package ai.core.server.messaging;

import ai.core.utils.JsonUtil;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class RpcClient {
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(15);

    /**
     * Build an RPC response JSON for the handler to publish.
     */
    public static String okResponse(String payloadJson) {
        return JsonUtil.toJson(Map.of("ok", Boolean.TRUE, "payload", payloadJson));
    }

    public static String errorResponse(String message) {
        return JsonUtil.toJson(Map.of("ok", Boolean.FALSE, "message", message != null ? message : "unknown error"));
    }

    static <T> T parseResponse(String json, Class<T> responseType) {
        var map = JsonUtil.fromJson(Map.class, json);
        var ok = Boolean.TRUE.equals(map.get("ok"));
        if (!ok) {
            var message = (String) map.get("message");
            throw new RuntimeException("RPC error: " + (message != null ? message : "unknown error"));
        }
        var payload = (String) map.get("payload");
        return JsonUtil.fromJson(responseType, payload);
    }

    private final SessionOwnershipRegistry ownershipRegistry;
    private final RpcTransport httpTransport;

    public RpcClient(RpcTransport httpTransport) {
        this(null, httpTransport);
    }

    public RpcClient(SessionOwnershipRegistry ownershipRegistry, RpcTransport httpTransport) {
        this.ownershipRegistry = ownershipRegistry;
        this.httpTransport = httpTransport;
    }

    public <T> T call(SessionCommand command, Class<T> responseType, Duration timeout) {
        var owner = ownershipRegistry == null ? null : ownershipRegistry.getOwner(command.sessionId());
        if (owner == null || owner.isBlank()) {
            throw new RpcNotDeliveredException("no session owner, sessionId=" + command.sessionId());
        }
        return dispatch(owner, command, responseType, timeout);
    }

    /**
     * Convenience method with default timeout.
     */
    public <T> T call(SessionCommand command, Class<T> responseType) {
        return call(command, responseType, DEFAULT_TIMEOUT);
    }

    public <T> T callToPod(String hostname, SessionCommand command, Class<T> responseType, Duration timeout) {
        if (hostname == null || hostname.isBlank()) {
            return call(command, responseType, timeout);
        }
        return dispatch(hostname, command, responseType, timeout);
    }

    public <T> T callToPod(String hostname, SessionCommand command, Class<T> responseType) {
        return callToPod(hostname, command, responseType, DEFAULT_TIMEOUT);
    }

    private <T> T dispatch(String targetPod, SessionCommand command, Class<T> responseType, Duration timeout) {
        var json = httpTransport.call(targetPod, command, timeout);
        return parseResponse(json, responseType);
    }

    /**
     * Create a new RPC command payload with a unique requestId.
     */
    public String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
