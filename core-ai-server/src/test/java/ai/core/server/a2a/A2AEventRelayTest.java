package ai.core.server.a2a;

import ai.core.api.a2a.StreamResponse;
import ai.core.api.a2a.TaskState;
import ai.core.api.a2a.TaskStatus;
import ai.core.api.a2a.TaskStatusUpdateEvent;
import ai.core.server.messaging.PodRegistry;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class A2AEventRelayTest {
    @Test
    void localSubscriptionReceivesEventsAndClosesOnTerminal() {
        var relay = new A2AEventRelay(mock(PodRegistry.class), "token");
        var events = new ArrayList<StreamResponse>();
        var closed = new AtomicBoolean();
        relay.subscribeLocal("t1", events::add, () -> closed.set(true));

        relay.publish("t1", status(TaskState.WORKING));
        assertFalse(closed.get());

        relay.publish("t1", status(TaskState.COMPLETED));
        assertEquals(2, events.size());
        assertTrue(closed.get());

        relay.publish("t1", status(TaskState.WORKING));
        assertEquals(2, events.size());
    }

    @Test
    void remoteSubscriptionRequiresAnAddressableOwner() {
        var relay = new A2AEventRelay(mock(PodRegistry.class), "token");

        assertThrows(IllegalStateException.class, () -> relay.subscribeRemote("pod-2", "t1", event -> {
        }, null));
    }

    private StreamResponse status(TaskState state) {
        var update = new TaskStatusUpdateEvent();
        update.taskId = "t1";
        update.status = TaskStatus.of(state);
        return StreamResponse.ofStatusUpdate(update);
    }
}
