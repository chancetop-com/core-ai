package ai.core.server.session;

import ai.core.server.messaging.EventPublisher;
import ai.core.server.messaging.TurnStateRegistry;
import ai.core.server.web.sse.SseEventBridge;
import ai.core.session.InProcessAgentSession;

/**
 * Wires a rebuilt or newly created session to the listeners that observe it. Extracted from
 * {@link AgentSessionManager} (file-size budget); the ordering is the point — the turn-state listener
 * must be registered before the SSE bridge so the Redis turn key exists before other pods see RUNNING,
 * and it is given the owner so per-user turn accounting works.
 *
 * @author stephen
 */
public final class SessionEventListenerWiring {
    public static void attach(InProcessAgentSession session, String sessionId, String userId, Deps deps) {
        // Turn-state listener goes first so the Redis turn key is written before the
        // RUNNING event is published to other pods. The liveness probe lets the registry's
        // heartbeat drop the key once the turn stops executing, even if no terminal event arrived.
        if (deps.turnStateRegistry() != null) {
            session.onEvent(deps.turnStateRegistry().listener(sessionId, userId, session::isTurnRunning));
        }
        session.onEvent(deps.chatMessageService().listener(sessionId));
        // Long-running turns may be walked away from: notify the owner on their own channel when one ends.
        if (deps.sessionCompletionNotifier() != null) {
            session.onEvent(deps.sessionCompletionNotifier().listener(sessionId));
        }
        session.onEvent(new SseEventBridge(sessionId, deps.eventPublisher()));
    }

    private SessionEventListenerWiring() {
    }

    /** Bundled collaborators: the method already takes the session, its id and its owner. */
    public record Deps(TurnStateRegistry turnStateRegistry, ChatMessageService chatMessageService,
                       SessionCompletionNotifier sessionCompletionNotifier, EventPublisher eventPublisher) {
    }
}
