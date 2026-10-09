package ai.core.cli.appserver;

import ai.core.api.server.session.CustomEvent;
import ai.core.api.server.session.TextChunkEvent;
import ai.core.api.server.session.ToolApprovalRequestEvent;
import ai.core.api.server.session.TurnCompleteEvent;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class EventBridgeTest {
    private final List<String> methods = new ArrayList<>();
    private final List<ObjectNode> params = new ArrayList<>();
    private final EventBridge bridge = new EventBridge("s1", (method, payload) -> {
        methods.add(method);
        params.add((ObjectNode) payload);
    });

    @Test
    void forwardsTextChunkWithTypeTag() {
        bridge.onTextChunk(TextChunkEvent.of("s1", "hello"));
        assertEquals("session/event", methods.getFirst());
        var notification = params.getFirst();
        assertEquals("s1", notification.path("sessionId").asText());
        assertEquals("text_chunk", notification.path("event").path("type").asText());
        assertEquals("hello", notification.path("event").path("chunk").asText());
    }

    @Test
    void forwardsTurnComplete() {
        bridge.onTurnComplete(TurnCompleteEvent.of("s1", "done"));
        var event = params.getFirst().path("event");
        assertEquals("turn_complete", event.path("type").asText());
        assertEquals("done", event.path("output").asText());
    }

    @Test
    void forwardsApprovalRequest() {
        bridge.onToolApprovalRequest(ToolApprovalRequestEvent.of("s1", "c1", "write_file", "{}", "write_file(a.txt)"));
        var event = params.getFirst().path("event");
        assertEquals("tool_approval_request", event.path("type").asText());
        assertEquals("write_file(a.txt)", event.path("suggestedPattern").asText());
    }

    @Test
    void forwardsCustomEvent() {
        bridge.onCustomEvent(CustomEvent.of("s1", "quick_replies", "{\"options\":[]}", null, "c1"));
        var event = params.getFirst().path("event");
        assertEquals("custom", event.path("type").asText());
        assertEquals("quick_replies", event.path("name").asText());
        assertTrue(event.path("callId").isValueNode());
    }
}
