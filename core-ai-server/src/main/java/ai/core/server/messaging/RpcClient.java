package ai.core.server.messaging;

import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

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
        return JsonUtil.toJson(Map.of("ok", true, "payload", payloadJson));
    }

    public static String errorResponse(String message) {
        return JsonUtil.toJson(Map.of("ok", false, "message", message));
    }

    private final JedisPool jedisPool;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final ConcurrentMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    public RpcClient(JedisPool jedisPool, SessionOwnershipRegistry ownershipRegistry) {
        this.jedisPool = jedisPool;
        this.ownershipRegistry = ownershipRegistry;
    }

    /**
     * Dispatch a response received from Redis Pub/Sub to the matching pending call.
     */
    public void onResponse(String requestId, String json) {
        var future = pending.remove(requestId);
        if (future != null) {
            future.complete(json);
        } else {
            LOGGER.debug("received RPC response for unknown requestId={}, ignoring", requestId);
        }
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
        return callInternal(resolveTargetStream(command.sessionId()), command, responseType, timeout);
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
        return callInternal(SessionCommand.podStreamKey(hostname), command, responseType, timeout);
    }

    public <T> T callToPod(String hostname, SessionCommand command, Class<T> responseType) {
        return callToPod(hostname, command, responseType, DEFAULT_TIMEOUT);
    }

    private <T> T callInternal(String targetStream, SessionCommand command, Class<T> responseType, Duration timeout) {
        var requestId = command.requestId();
        if (requestId == null) {
            throw new IllegalArgumentException("RPC command must have a non-null requestId");
        }

        var future = new CompletableFuture<String>();
        pending.put(requestId, future);

        try {
            publishCommand(targetStream, command);

            var json = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            return parseResponse(json, responseType);
        } catch (java.util.concurrent.TimeoutException e) {
            pending.remove(requestId);
            throw new RuntimeException("RPC call timed out after " + timeout.toSeconds()
                    + "s, type=" + command.type() + ", sessionId=" + command.sessionId(), e);
        } catch (java.util.concurrent.CancellationException e) {
            pending.remove(requestId);
            throw new RuntimeException("RPC call cancelled, type=" + command.type() + ", sessionId=" + command.sessionId(), e);
        } catch (InterruptedException e) {
            pending.remove(requestId);
            Thread.currentThread().interrupt();
            throw new RuntimeException("RPC call interrupted, type=" + command.type() + ", sessionId=" + command.sessionId(), e);
        } catch (Exception e) {
            pending.remove(requestId);
            throw new RuntimeException("RPC call failed, type=" + command.type() + ", sessionId=" + command.sessionId(), e);
        }
    }

    private void publishCommand(String targetStream, SessionCommand command) {
        try (var jedis = jedisPool.getResource()) {
            jedis.xadd(targetStream, StreamEntryID.NEW_ENTRY, command.toStreamMap());
            LOGGER.debug("RPC command published to stream={}, type={}, sessionId={}, requestId={}",
                    targetStream, command.type(), command.sessionId(), command.requestId());
        }
    }

    private String resolveTargetStream(String sessionId) {
        var owner = ownershipRegistry.getOwner(sessionId);
        if (owner != null) {
            return SessionCommand.podStreamKey(owner);
        }
        return SessionCommand.UNOWNED_STREAM;
    }

    private <T> T parseResponse(String json, Class<T> responseType) {
        var map = JsonUtil.fromJson(Map.class, json);
        var ok = Boolean.TRUE.equals(map.get("ok"));
        if (!ok) {
            var message = (String) map.get("message");
            throw new RuntimeException("RPC error: " + (message != null ? message : "unknown error"));
        }
        var payload = (String) map.get("payload");
        return JsonUtil.fromJson(responseType, payload);
    }

    /**
     * Create a new RPC command payload with a unique requestId.
     */
    public String newRequestId() {
        return UUID.randomUUID().toString();
    }
}
