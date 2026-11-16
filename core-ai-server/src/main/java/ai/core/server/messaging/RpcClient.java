package ai.core.server.messaging;

import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * @author stephen
 */
public class RpcClient {
    private static final Logger LOGGER = LoggerFactory.getLogger(RpcClient.class);
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
    private final RedisRpcTransport redisTransport;
    private final RpcTransport httpTransport;

    public RpcClient(JedisPool jedisPool, SessionOwnershipRegistry ownershipRegistry) {
        this(ownershipRegistry, new RedisRpcTransport(jedisPool), null);
    }

    public RpcClient(SessionOwnershipRegistry ownershipRegistry, RedisRpcTransport redisTransport, RpcTransport httpTransport) {
        this.ownershipRegistry = ownershipRegistry;
        this.redisTransport = redisTransport;
        this.httpTransport = httpTransport;
    }

    /**
     * Publish a command to the owner's per-Pod stream and block waiting for the response.
     *
     * @param command      the command to publish (must have a non-null requestId)
     * @param responseType the expected response class
     * @param timeout      max wait duration
     * @return deserialized response
     */
    public <T> T call(SessionCommand command, Class<T> responseType, Duration timeout) {
        return dispatch(ownershipRegistry.getOwner(command.sessionId()), command, responseType, timeout);
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
        String json;
        if (httpTransport != null && targetPod != null) {
            try {
                json = httpTransport.call(targetPod, command, timeout);
            } catch (RpcNotDeliveredException e) {
                LOGGER.info("rpc http not delivered, falling back to redis, targetPod={}, type={}, sessionId={}, reason={}",
                    targetPod, command.type(), command.sessionId(), e.getMessage());
                json = redisTransport.call(targetPod, command, timeout);
            }
        } else {
            json = redisTransport.call(targetPod, command, timeout);
        }
        return parseResponse(json, responseType);
    }

    /**
     * Dispatch a response received from Redis Pub/Sub to the matching pending call.
     */
    public void onResponse(String requestId, String json) {
        redisTransport.onResponse(requestId, json);
    }

    /**
     * Create a new RPC command payload with a unique requestId.
     */
    public String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
