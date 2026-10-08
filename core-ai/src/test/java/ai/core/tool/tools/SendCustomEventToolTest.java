package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.api.server.session.AgentEvent;
import ai.core.api.server.session.CustomEvent;
import ai.core.session.CustomEventEmitter;
import ai.core.session.SessionCustomEventEmitter;
import core.framework.json.JSON;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class SendCustomEventToolTest {
    private final AtomicBoolean turnActive = new AtomicBoolean(true);
    private final List<AgentEvent> dispatched = new ArrayList<>();
    private final SendCustomEventTool tool = SendCustomEventTool.builder().build();
    private final ExecutionContext context = ExecutionContext.builder()
        .customVariable(CustomEventEmitter.CONTEXT_KEY, new SessionCustomEventEmitter("s-1", dispatched::add, turnActive::get))
        .build();

    @Test
    void sendsANamedEventWithTheJsonPayload() {
        var result = tool.execute(JSON.toJSON(Map.of("event_name", "menu_table", "data", Map.of("title", "Menu A"))), context);

        assertFalse(result.isFailed());
        assertEquals(1, dispatched.size());
        var event = (CustomEvent) dispatched.getFirst();
        assertEquals("menu_table", event.name);
        var payload = JSON.fromJSON(Map.class, event.data);
        assertEquals("Menu A", payload.get("title"));
        assertNull(event.callId, "without a tool executor there is no call id to attach");
    }

    @Test
    void failsWithoutASessionEventChannel() {
        var result = tool.execute(JSON.toJSON(Map.of("event_name", "menu_table", "data", Map.of())), ExecutionContext.builder().build());

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("event channel"));
    }

    @Test
    void failsWhenTheTurnIsNotRunning() {
        turnActive.set(false);

        var result = tool.execute(JSON.toJSON(Map.of("event_name", "menu_table", "data", Map.of())), context);

        assertTrue(result.isFailed());
        assertTrue(result.getResult().contains("turn"));
    }

    @Test
    void failsWithoutAnEventName() {
        var result = tool.execute(JSON.toJSON(Map.of("event_name", " ", "data", Map.of())), context);

        assertTrue(result.isFailed());
        assertTrue(dispatched.isEmpty());
    }
}
