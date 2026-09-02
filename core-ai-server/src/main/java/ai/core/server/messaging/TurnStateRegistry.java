package ai.core.server.messaging;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.params.SetParams;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BooleanSupplier;

/**
 * Cross-pod source of truth for "is a turn currently executing on this session".
 *
 * The pod executing a turn writes a Redis key at turn start and deletes it at turn end
 * (complete/error/cancel). A heartbeat renews the key while the turn runs, so a crashed
 * pod's keys expire within TURN_TTL and status degrades to idle — exactly the signal the
 * frontend watchdog needs to re-sync history. Local SSE channel state stays pod-local;
 * this registry lets the status endpoint answer truthfully from any pod.
 *
 * @author xander
 */
public class TurnStateRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(TurnStateRegistry.class);
    private static final String TURN_KEY_PREFIX = "session:turn:";
    private static final Duration TURN_TTL = Duration.ofSeconds(90);
    private static final Duration RENEW_INTERVAL = Duration.ofSeconds(30);

    private final JedisPool jedisPool;
    // sessionId -> "is that turn still executing here". The heartbeat consults the supplier before
    // renewing, so a turn whose thread died takes the TTL path instead of being renewed forever.
    private final Map<String, BooleanSupplier> runningTurns = new ConcurrentHashMap<>();
    private volatile boolean heartbeatActive = true;
    private Thread heartbeatThread;

    public TurnStateRegistry(JedisPool jedisPool) {
        this.jedisPool = jedisPool;
    }

    public void markRunning(String sessionId) {
        markRunning(sessionId, null);
    }

    /**
     * @param turnLiveness answers "is this turn still executing on this pod"; the heartbeat stops
     *                     renewing (and clears) as soon as it says no. Null means always alive,
     *                     which only leaves the TTL as protection — pass a real check where possible.
     */
    public void markRunning(String sessionId, BooleanSupplier turnLiveness) {
        runningTurns.put(sessionId, turnLiveness != null ? turnLiveness : () -> true);
        writeKey(sessionId);
    }

    public void clear(String sessionId) {
        runningTurns.remove(sessionId);
        try (var jedis = jedisPool.getResource()) {
            jedis.del(turnKey(sessionId));
        } catch (Exception e) {
            LOGGER.warn("failed to clear turn state (Redis unavailable), sessionId={}", sessionId, e);
        }
    }

    public TurnLiveness liveness(String sessionId) {
        try (var jedis = jedisPool.getResource()) {
            return jedis.get(turnKey(sessionId)) != null ? TurnLiveness.RUNNING : TurnLiveness.NOT_RUNNING;
        } catch (Exception e) {
            LOGGER.warn("failed to read turn state (Redis unavailable), sessionId={}", sessionId, e);
            return TurnLiveness.UNKNOWN;
        }
    }

    /**
     * Session event listener that mirrors the turn lifecycle into Redis. Register it
     * before the SSE bridge so the key is written before the RUNNING event reaches
     * other pods.
     */
    public AgentEventListener listener(String sessionId) {
        return listener(sessionId, null);
    }

    public AgentEventListener listener(String sessionId, BooleanSupplier turnLiveness) {
        return new AgentEventListener() {
            @Override
            public void onStatusChange(StatusChangeEvent event) {
                if (event.status == SessionStatus.RUNNING) {
                    markRunning(sessionId, turnLiveness);
                } else {
                    clear(sessionId);
                }
            }

            @Override
            public void onTurnComplete(TurnCompleteEvent event) {
                clear(sessionId);
            }
        };
    }

    void renewAll() {
        for (var entry : runningTurns.entrySet()) {
            var sessionId = entry.getKey();
            if (!entry.getValue().getAsBoolean()) {
                // The turn behind this key is gone but nothing cleared it — renewing would pin the
                // session to "running" for the lifetime of the pod. Clear it so status flips to idle
                // and the frontend re-syncs from history.
                LOGGER.warn("turn is no longer executing but its state was never cleared, clearing now, sessionId={}", sessionId);
                clear(sessionId);
                continue;
            }
            writeKey(sessionId);
        }
    }

    public void start() {
        heartbeatThread = Thread.ofVirtual().name("turn-state-heartbeat").start(this::heartbeatLoop);
    }

    public void stop() {
        heartbeatActive = false;
        if (heartbeatThread != null) {
            heartbeatThread.interrupt();
        }
        // Turns die with this pod — delete their keys so status flips to idle immediately
        // instead of waiting for TTL expiry.
        for (var sessionId : List.copyOf(runningTurns.keySet())) {
            clear(sessionId);
        }
    }

    private void heartbeatLoop() {
        while (heartbeatActive) {
            try {
                Thread.sleep(RENEW_INTERVAL.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            if (heartbeatActive) {
                renewAll();
            }
        }
    }

    private void writeKey(String sessionId) {
        try (var jedis = jedisPool.getResource()) {
            jedis.set(turnKey(sessionId), "running", SetParams.setParams().ex((int) TURN_TTL.toSeconds()));
        } catch (Exception e) {
            LOGGER.warn("failed to write turn state (Redis unavailable), sessionId={}", sessionId, e);
        }
    }

    private String turnKey(String sessionId) {
        return TURN_KEY_PREFIX + sessionId;
    }

    public enum TurnLiveness {
        RUNNING, NOT_RUNNING, UNKNOWN
    }
}
