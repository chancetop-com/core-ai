package ai.core.server.messaging;

import ai.core.api.server.session.SessionStatus;
import ai.core.api.server.session.StatusChangeEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TurnStateRegistryTest {
    private static StatusChangeEvent status(SessionStatus status) {
        var event = new StatusChangeEvent();
        event.status = status;
        return event;
    }

    @Test
    void turnStateSurvivesTerminalWhileDeferredWorkIsPending() {
        var store = new FakeStore();
        var registry = new TurnStateRegistry(store, "pod-1");
        var pendingWork = new boolean[]{true};
        var listener = registry.listener("s1", "u1", () -> true, () -> pendingWork[0]);

        listener.onStatusChange(status(SessionStatus.RUNNING));
        assertEquals(List.of("s1"), store.marked);

        listener.onTurnComplete(new TurnCompleteEvent());
        assertTrue(store.messageClears.isEmpty());

        listener.onStatusChange(status(SessionStatus.IDLE));
        assertTrue(store.messageClears.isEmpty());

        pendingWork[0] = false;
        listener.onTurnComplete(new TurnCompleteEvent());
        assertEquals(List.of("s1"), store.messageClears);
    }

    @Test
    void legacyListenerStillClearsOnTerminal() {
        var store = new FakeStore();
        var registry = new TurnStateRegistry(store, "pod-1");
        var listener = registry.listener("s1", "u1", () -> true);

        listener.onStatusChange(status(SessionStatus.RUNNING));
        listener.onTurnComplete(new TurnCompleteEvent());

        assertEquals(List.of("s1"), store.messageClears);
    }

    private static final class FakeStore implements TurnStateStore {
        private final List<String> marked = new ArrayList<>();
        private final List<String> messageClears = new ArrayList<>();

        @Override
        public void markRunning(String sessionId, String userId, String ownerPod) {
            marked.add(sessionId);
        }

        @Override
        public void clear(String sessionId, String userId, String ownerPod) {
            messageClears.add(sessionId);
        }

        @Override
        public void renew(String sessionId, String userId) {
        }

        @Override
        public TurnStateRegistry.TurnLiveness liveness(String sessionId) {
            return TurnStateRegistry.TurnLiveness.NOT_RUNNING;
        }

        @Override
        public int inFlightTurns(String userId) {
            return 0;
        }
    }
}
