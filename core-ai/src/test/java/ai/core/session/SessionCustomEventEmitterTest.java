package ai.core.session;

import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.CustomEvent;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SessionCustomEventEmitterTest {
    private final AtomicBoolean turnActive = new AtomicBoolean();
    private final List<AgentEvent> dispatched = new ArrayList<>();

    @Test
    void emitsWhileATurnIsRunning() {
        turnActive.set(true);
        var emitter = emitter();

        var reason = emitter.emit("menu_table", "{\"rows\":[]}", "call-1");

        assertNull(reason);
        assertEquals(1, dispatched.size());
        var event = (CustomEvent) dispatched.getFirst();
        assertEquals("s-1", event.sessionId);
        assertEquals("menu_table", event.name);
        assertEquals("{\"rows\":[]}", event.data);
        assertEquals("call-1", event.callId);
    }

    @Test
    void rejectsEmissionsOutsideARunningTurn() {
        var emitter = emitter();

        var reason = emitter.emit("menu_table", "{}", null);

        assertNotNull(reason);
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void rejectsAnOversizedPayload() {
        turnActive.set(true);
        var emitter = emitter();
        var oversized = "x".repeat(256 * 1024 + 1);

        var reason = emitter.emit("menu_table", oversized, null);

        assertNotNull(reason);
        assertTrue(dispatched.isEmpty());
    }

    @Test
    void enforcesThePerTurnBudgetAndResetsWhenATurnStarts() {
        turnActive.set(true);
        var emitter = emitter();
        var payload = "x".repeat(200 * 1024);

        for (int i = 0; i < 5; i++) {
            assertNull(emitter.emit("menu_table", payload, null), "payload " + i + " fits into the 1 MB turn budget");
        }
        assertNotNull(emitter.emit("menu_table", payload, null), "the sixth payload must exceed the budget");
        assertEquals(5, dispatched.size());

        emitter.resetTurn();
        assertNull(emitter.emit("menu_table", payload, null), "a new turn starts with a fresh budget");
        assertEquals(6, dispatched.size());
    }

    @Test
    void requiresANameAndData() {
        turnActive.set(true);
        var emitter = emitter();

        assertNotNull(emitter.emit("  ", "{}", null));
        assertNotNull(emitter.emit("menu_table", null, null));
        assertTrue(dispatched.isEmpty());
    }

    private SessionCustomEventEmitter emitter() {
        return new SessionCustomEventEmitter("s-1", dispatched::add, turnActive::get);
    }
}
