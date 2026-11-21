package ai.core.server.web.sse;

import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.sse.SseBaseEvent;
import ai.core.api.server.session.sse.SseErrorEvent;
import ai.core.api.server.session.sse.SseStatusChangeEvent;
import ai.core.api.server.session.sse.SseTaskStatusEvent;
import ai.core.api.server.session.sse.SseTurnCompleteEvent;
import ai.core.server.messaging.PodRegistry;
import ai.core.server.messaging.SessionOwnershipRegistry;
import core.framework.web.sse.Channel;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Directed SSE delivery: the owner pod hosts the event hub; every other pod follows the owner
 * through an internal SSE stream and falls back to the Redis broadcast when the relay is not
 * available (rolling upgrade / owner is still an old pod).
 *
 * @author stephen
 */
@SuppressWarnings("PMD.CompareObjectsWithEquals")
@SuppressFBWarnings("VO_VOLATILE_INCREMENT")
public class DirectedSessionDelivery {
    private static final Logger LOGGER = LoggerFactory.getLogger(DirectedSessionDelivery.class);
    private static final int MAX_PENDING_EVENTS = 1_000;
    // after this many consecutive failed relay attempts, give the browser a chance to recover through
    // its normal reconnect/history path instead of leaving it on a silent keepalive-only stream
    private static final int MAX_RELAY_FAILURES = 12;

    private final SessionEventHubRegistry hubRegistry;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final PodRegistry podRegistry;
    private final String internalToken;
    private final ChannelService channelService;
    private final int maxRelayFailures;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();
    private final ScheduledExecutorService watchdog = Executors.newSingleThreadScheduledExecutor(
            Thread.ofVirtual().name("sse-directed-watchdog").factory());
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public DirectedSessionDelivery(SessionEventHubRegistry hubRegistry, SessionOwnershipRegistry ownershipRegistry,
                                   PodRegistry podRegistry, String internalToken, ChannelService channelService) {
        this(hubRegistry, ownershipRegistry, podRegistry, internalToken, channelService, MAX_RELAY_FAILURES);
    }

    DirectedSessionDelivery(SessionEventHubRegistry hubRegistry, SessionOwnershipRegistry ownershipRegistry,
                            PodRegistry podRegistry, String internalToken, ChannelService channelService,
                            int maxRelayFailures) {
        this.hubRegistry = hubRegistry;
        this.ownershipRegistry = ownershipRegistry;
        this.podRegistry = podRegistry;
        this.internalToken = internalToken;
        this.channelService = channelService;
        this.maxRelayFailures = maxRelayFailures;
    }

    public void start() {
        watchdog.scheduleWithFixedDelay(this::checkOwners, 5, 5, TimeUnit.SECONDS);
    }

    public void stop() {
        watchdog.shutdownNow();
        for (var state : states.values()) {
            synchronized (state) {
                state.generation++;
                stopRelay(state);
            }
        }
        states.clear();
    }

    public boolean hasHealthyRelay(String sessionId) {
        var state = states.get(sessionId);
        return state != null && state.relayActive;
    }

    public void connect(Channel<SseBaseEvent> channel, String sessionId) {
        var state = states.computeIfAbsent(sessionId, k -> new State());
        synchronized (state) {
            channelService.connect(channel, sessionId);
            stopRelay(state);
            state.channel = channel;
            state.generation++;
            state.lastStatus = SessionStatus.IDLE;
            state.relayActive = false;
            state.relayPending = true;
            state.pending.clear();
            state.nullOwnerCount = 0;
            state.relayFailures = 0;
            startResolver(sessionId, state, state.generation);
        }
    }

    public void onBroadcast(String sessionId, SseBaseEvent event) {
        var state = states.get(sessionId);
        if (state == null) return;
        synchronized (state) {
            if (state.relayActive) return;
            if (state.relayPending) {
                if (state.pending.size() < MAX_PENDING_EVENTS) state.pending.addLast(event);
                return;
            }
        }
        channelService.send(sessionId, event);
    }

    public SessionStatus status(String sessionId) {
        var hub = hubRegistry.get(sessionId);
        if (hub != null) return hub.status();
        var state = states.get(sessionId);
        return state == null ? SessionStatus.IDLE : state.lastStatus;
    }

    public void close(String sessionId) {
        var state = states.remove(sessionId);
        if (state != null) {
            synchronized (state) {
                state.generation++;
                stopRelay(state);
                state.channel = null;
            }
        }
        channelService.close(sessionId);
        hubRegistry.remove(sessionId);
    }

    public void closeIfCurrent(String sessionId, Channel<SseBaseEvent> channel) {
        var state = states.get(sessionId);
        if (state != null && channelService.closeIfCurrent(sessionId, channel)) {
            synchronized (state) {
                state.generation++;
                stopRelay(state);
                state.channel = null;
            }
            states.remove(sessionId, state);
        }
    }

    public long bufferedBytes() {
        return hubRegistry.bufferedBytes();
    }

    public long bufferedEvents() {
        return hubRegistry.bufferedEvents();
    }

    public long droppedTotal() {
        return hubRegistry.droppedTotal();
    }

    private void startResolver(String sessionId, State state, long generation) {
        state.resolver = Thread.ofVirtual()
                .name("session-directed-" + sessionId)
                .start(() -> resolve(sessionId, state, generation));
    }

    private void resolve(String sessionId, State state, long generation) {
        int fastPolls = 0;
        int backoffMs = 200;
        while (current(sessionId, state, generation) && !Thread.currentThread().isInterrupted()) {
            var owner = owner(sessionId);
            if (owner == null) {
                if (fastPolls++ < 20) sleepQuietly(100);
                else sleepQuietly(5_000);
                continue;
            }
            fastPolls = 0;
            if (!bindOwner(sessionId, state, generation, owner)) return;
            if (owner.equals(ownershipRegistry.getHostname())) {
                subscribeLocalHub(sessionId, state, generation);
                return;
            }
            if (!runRelay(sessionId, state, generation, owner)) return;
            sleepQuietly(backoffMs);
            backoffMs = Math.min(backoffMs * 2, 5_000);
        }
    }

    private String owner(String sessionId) {
        try {
            return ownershipRegistry.getOwner(sessionId);
        } catch (RuntimeException e) {
            LOGGER.warn("failed to resolve session owner, sessionId={}", sessionId, e);
            sleepQuietly(1_000);
            return null;
        }
    }

    private boolean bindOwner(String sessionId, State state, long generation, String owner) {
        synchronized (state) {
            if (!current(sessionId, state, generation)) return false;
            state.owner = owner;
            return true;
        }
    }

    private boolean runRelay(String sessionId, State state, long generation, String owner) {
        if (podRegistry == null) {
            sleepQuietly(1_000);
            return true;
        }
        var address = podRegistry.address(owner);
        if (address == null) {
            sleepQuietly(1_000);
            return true;
        }
        var relay = new SessionRelayClient(sessionId, address, internalToken, 0, httpClient,
                new RelayListener(sessionId, state, generation, owner));
        synchronized (state) {
            if (!current(sessionId, state, generation)) {
                relay.close();
                return false;
            }
            state.relay = relay;
        }
        relay.run();
        synchronized (state) {
            if (!current(sessionId, state, generation)) return false;
            if (state.relay == relay) state.relay = null;
            state.relayActive = false;
            state.relayPending = false;
        }
        return current(sessionId, state, generation);
    }

    private void subscribeLocalHub(String sessionId, State state, long generation) {
        var hub = hubRegistry.getOrCreate(sessionId);
        var subscription = hub.subscribe(0, (seq, event) -> deliver(sessionId, state, generation, seq, event));
        synchronized (state) {
            if (!current(sessionId, state, generation)) {
                subscription.close();
                return;
            }
            state.hubSubscription = subscription;
            state.relayActive = true;
            state.relayPending = false;
            state.pending.clear();
            state.resolver = null;
        }
    }

    private boolean deliver(String sessionId, State state, long generation, long seq, SseBaseEvent event) {
        synchronized (state) {
            if (!current(sessionId, state, generation)) return false;
            if (event instanceof SseStatusChangeEvent statusChange && statusChange.status != null) {
                state.lastStatus = statusChange.status;
            } else if (event instanceof SseTurnCompleteEvent) {
                state.lastStatus = SessionStatus.IDLE;
            } else if (event instanceof SseErrorEvent) {
                state.lastStatus = SessionStatus.ERROR;
            } else if (!(event instanceof SseTaskStatusEvent)) {
                state.lastStatus = SessionStatus.RUNNING;
            }
        }
        return channelService.send(sessionId, seq > 0 ? String.valueOf(seq) : null, event);
    }

    private void flushPending(String sessionId, State state, long generation) {
        List<SseBaseEvent> pending;
        synchronized (state) {
            if (!current(sessionId, state, generation)) return;
            state.relayActive = false;
            state.relayPending = false;
            pending = new ArrayList<>(state.pending);
            state.pending.clear();
        }
        for (var event : pending) channelService.send(sessionId, event);
    }

    private void checkOwners() {
        for (var entry : states.entrySet()) {
            var sessionId = entry.getKey();
            var state = entry.getValue();
            try {
                boolean stale;
                synchronized (state) {
                    stale = state.channel != null && state.relay != null && state.relay.isStale();
                }
                if (stale) {
                    synchronized (state) {
                        if (state.relay != null) state.relay.close();
                    }
                    continue;
                }

                var owner = ownershipRegistry.getOwner(sessionId);
                if (owner == null) {
                    boolean close;
                    synchronized (state) {
                        close = ++state.nullOwnerCount >= 6;
                    }
                    if (close) closeDirected(sessionId, state);
                    continue;
                }

                boolean restart = false;
                synchronized (state) {
                    state.nullOwnerCount = 0;
                    if (state.owner != null && !state.owner.equals(owner)) restart = true;
                    if (state.hubSubscription != null && !owner.equals(ownershipRegistry.getHostname())) restart = true;
                }
                if (restart) restartDirected(sessionId, state);
            } catch (RuntimeException e) {
                LOGGER.warn("failed to check directed session owner, sessionId={}", sessionId, e);
            }
        }
    }

    private void restartDirected(String sessionId, State state) {
        synchronized (state) {
            state.generation++;
            stopRelay(state);
            state.relayActive = false;
            state.relayPending = true;
            state.owner = null;
            state.pending.clear();
            state.relayFailures = 0;
            startResolver(sessionId, state, state.generation);
        }
    }

    private void closeDirected(String sessionId, State state) {
        synchronized (state) {
            state.generation++;
            stopRelay(state);
            var channel = state.channel;
            state.channel = null;
            if (channel != null) channelService.closeIfCurrent(sessionId, channel);
        }
        states.remove(sessionId, state);
    }

    private void stopRelay(State state) {
        if (state.hubSubscription != null) {
            state.hubSubscription.close();
            state.hubSubscription = null;
        }
        if (state.relay != null) {
            state.relay.close();
            state.relay = null;
        }
        if (state.resolver != null) {
            state.resolver.interrupt();
            state.resolver = null;
        }
        state.relayActive = false;
        state.relayPending = false;
        state.owner = null;
    }

    private boolean current(String sessionId, State state, long generation) {
        return state.generation == generation && states.get(sessionId) == state;
    }

    private void sleepQuietly(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private final class RelayListener implements SessionRelayClient.Listener {
        private final String sessionId;
        private final State state;
        private final long generation;
        private final String owner;

        private RelayListener(String sessionId, State state, long generation, String owner) {
            this.sessionId = sessionId;
            this.state = state;
            this.generation = generation;
            this.owner = owner;
        }

        @Override
        public void onOpen() {
            synchronized (state) {
                if (!current(sessionId, state, generation)) return;
                state.relayActive = true;
                state.relayPending = false;
                state.relayFailures = 0;
                state.pending.clear();
            }
        }

        @Override
        public boolean onEvent(long seq, SseBaseEvent event) {
            return deliver(sessionId, state, generation, seq, event);
        }

        @Override
        public void onUnavailable(int status) {
            int failures;
            synchronized (state) {
                if (!current(sessionId, state, generation)) return;
                failures = ++state.relayFailures;
            }
            if (failures >= maxRelayFailures) {
                LOGGER.warn("session relay failed repeatedly, closing the local channel so the client can recover, "
                                + "sessionId={}, owner={}, failures={}, status={}",
                        sessionId, owner, failures, status);
                closeDirected(sessionId, state);
                return;
            }
            LOGGER.info("session relay unavailable, sessionId={}, owner={}, status={}, failures={}",
                    sessionId, owner, status, failures);
            flushPending(sessionId, state, generation);
        }
    }

    private static final class State {
        final Deque<SseBaseEvent> pending = new ArrayDeque<>();
        volatile Channel<SseBaseEvent> channel;
        volatile String owner;
        volatile Thread resolver;
        volatile SessionRelayClient relay;
        volatile SessionEventHub.Subscription hubSubscription;
        volatile boolean relayActive;
        volatile boolean relayPending;
        volatile long generation;
        volatile SessionStatus lastStatus = SessionStatus.IDLE;
        int relayFailures;
        int nullOwnerCount;
    }
}
