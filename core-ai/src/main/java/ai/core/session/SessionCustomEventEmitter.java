package ai.core.session;

import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.CustomEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Session-scoped emitter: enforces the turn window and the per-turn payload budget, then dispatches the
 * event to the session's listeners. Events belong to a turn — the SSE layer drops late events once a turn
 * terminated, so an emission outside a running turn is rejected instead of silently lost.
 *
 * @author stephen
 */
public class SessionCustomEventEmitter implements CustomEventEmitter {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionCustomEventEmitter.class);
    private static final int MAX_EVENT_BYTES = 256 * 1024;
    private static final int MAX_TURN_BYTES = 1024 * 1024;

    private final String sessionId;
    private final Consumer<AgentEvent> dispatcher;
    private final BooleanSupplier turnActive;
    private final AtomicLong turnBytes = new AtomicLong();

    public SessionCustomEventEmitter(String sessionId, Consumer<AgentEvent> dispatcher, BooleanSupplier turnActive) {
        this.sessionId = sessionId;
        this.dispatcher = dispatcher;
        this.turnActive = turnActive;
    }

    /** Called when a turn starts, so the budget counts this turn's events only. */
    public void resetTurn() {
        turnBytes.set(0);
    }

    @Override
    public String emit(String name, String data, String callId) {
        if (name == null || name.isBlank()) return "event name is required";
        if (data == null) return "event data is required";
        if (!turnActive.getAsBoolean()) return "custom events can only be sent while a turn is running";
        var bytes = data.getBytes(StandardCharsets.UTF_8).length;
        if (bytes > MAX_EVENT_BYTES) return "event payload exceeds 256 KB; publish the content as a file and send its URL instead";
        if (turnBytes.addAndGet(bytes) > MAX_TURN_BYTES) {
            turnBytes.addAndGet(-bytes);
            return "the custom event budget for this turn (1 MB) is used up; publish the content as a file and send its URL instead";
        }
        LOGGER.debug("custom event dispatched, name={}, bytes={}, sessionId={}", name, bytes, sessionId);
        dispatcher.accept(CustomEvent.of(sessionId, name, data, callId));
        return null;
    }
}
