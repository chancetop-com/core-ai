package ai.core.server.messaging;

import ai.core.api.server.session.AgentEventListener;
import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TurnCompleteEvent;

import java.util.function.BooleanSupplier;

/**
 * Mirrors one session's turn lifecycle into the shared turn state. The state is kept alive while the
 * session still has deferred work (background bash/subagents), so other pods see the session as busy
 * and the workspace lease and idle sleeper do not take the runtime away.
 *
 * @author stephen
 */
final class TurnStateListener implements AgentEventListener {
    private final TurnStateRegistry registry;
    private final String sessionId;
    private final String userId;
    private final BooleanSupplier turnLiveness;
    private final BooleanSupplier pendingWork;

    TurnStateListener(TurnStateRegistry registry, String sessionId, String userId,
                      BooleanSupplier turnLiveness, BooleanSupplier pendingWork) {
        this.registry = registry;
        this.sessionId = sessionId;
        this.userId = userId;
        this.turnLiveness = turnLiveness;
        this.pendingWork = pendingWork;
    }

    @Override
    public void onStatusChange(StatusChangeEvent event) {
        if (event.status == SessionStatus.RUNNING) {
            registry.markRunning(sessionId, userId, turnLiveness);
        } else if (!hasPendingWork()) {
            registry.clear(sessionId);
        }
    }

    @Override
    public void onTurnComplete(TurnCompleteEvent event) {
        if (!hasPendingWork()) registry.clear(sessionId);
    }

    private boolean hasPendingWork() {
        return pendingWork != null && pendingWork.getAsBoolean();
    }
}
