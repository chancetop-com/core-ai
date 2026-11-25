package ai.core.server.messaging;

import ai.core.api.server.session.EventType;
import ai.core.api.server.session.sse.SseCustomEvent;
import ai.core.api.server.session.sse.SseLlmFirstTokenEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author stephen
 */
class SseEventHelperTest {
    @Test
    void customEventRoundTripsThroughTheTypeMaps() {
        var event = new SseCustomEvent();
        event.name = "menu_table";
        event.data = "{}";

        SseEventHelper.initEvent(event, "s-1");

        assertEquals(EventType.CUSTOM, event.type);
        assertEquals("s-1", event.sessionId);
        assertNotNull(event.timestamp);
        assertEquals(SseCustomEvent.class, SseEventHelper.EVENT_CLASSES.get(EventType.CUSTOM));
        assertEquals(EventType.CUSTOM, SseEventHelper.eventTypeFor(event));
    }

    @Test
    void firstTokenEventRoundTripsThroughTheTypeMaps() {
        var event = new SseLlmFirstTokenEvent();
        event.ttftMs = 1840L;

        SseEventHelper.initEvent(event, "s-1");

        assertEquals(EventType.LLM_FIRST_TOKEN, event.type);
        assertEquals(SseLlmFirstTokenEvent.class, SseEventHelper.EVENT_CLASSES.get(EventType.LLM_FIRST_TOKEN));
        assertEquals(EventType.LLM_FIRST_TOKEN, SseEventHelper.eventTypeFor(event));
    }
}
