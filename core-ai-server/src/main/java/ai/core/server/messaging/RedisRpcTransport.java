package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;

/**
 * @author stephen
 */
public class RedisRpcTransport implements RpcTransport {
    private static final Logger LOGGER = LoggerFactory.getLogger(RedisRpcTransport.class);

    private final JedisPool jedisPool;
    private final ConcurrentMap<String, CompletableFuture<String>> pending = new ConcurrentHashMap<>();

    public RedisRpcTransport(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    @Override
    @SuppressFBWarnings({"EXS_EXCEPTION_SOFTENING_HAS_CHECKED", "WEM_OBSCURING_EXCEPTION"})
    public String call(String targetPod, SessionCommand command, Duration timeout) {
        var requestId = command.requestId();
        if (requestId == null) {
            throw new IllegalArgumentException("RPC command must have a non-null requestId");
        }
        var targetStream = targetPod != null ? SessionCommand.podStreamKey(targetPod) : SessionCommand.UNOWNED_STREAM;
        var future = new CompletableFuture<String>();
        pending.put(requestId, future);
        try {
            publish(targetStream, command, requestId);
            return future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
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

    public void onResponse(String requestId, String json) {
        var future = pending.remove(requestId);
        if (future != null) {
            future.complete(json);
        } else {
            LOGGER.debug("received RPC response for unknown requestId={}, ignoring", requestId);
        }
    }

    private void publish(String targetStream, SessionCommand command, String requestId) {
        try (var jedis = jedisPool.getResource()) {
            jedis.xadd(targetStream, StreamEntryID.NEW_ENTRY, command.toStreamMap());
            LOGGER.debug("RPC command published to stream={}, type={}, sessionId={}, requestId={}",
                targetStream, command.type(), command.sessionId(), requestId);
        }
    }
}
