package ai.core.server.replay.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * ChatML decode path: string content must be normalized to the array form before
 * JsonUtil deserialization (the project mapper ignores @JsonDeserialize).
 *
 * @author stephen
 */
class ReplayRequestCodecTest {
    @Test
    void parseHandlesStringContent() {
        var request = ReplayRequestCodec.parse("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals(1, request.messages.size());
        assertEquals("hi", request.messages.getFirst().getTextContent());
    }

    @Test
    void parseHandlesArrayContentWithMixedParts() {
        var json = "{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"look\"},"
                + "{\"type\":\"image_url\",\"image_url\":{\"url\":\"https://example.com/a.png\"}}]}]}";

        var request = ReplayRequestCodec.parse(json);

        assertEquals(1, request.messages.size());
        assertEquals("look", request.messages.getFirst().getTextContent());
    }

    @Test
    void parseHandlesToolsAndToolCalls() {
        var json = "{\"messages\":[{\"role\":\"assistant\",\"content\":null,\"tool_calls\":[{\"id\":\"call-1\","
                + "\"function\":{\"name\":\"search\",\"arguments\":\"{\\\"q\\\":\\\"x\\\"}\"}}]},"
                + "{\"role\":\"tool\",\"tool_call_id\":\"call-1\",\"content\":\"result\"}],"
                + "\"tools\":[{\"type\":\"function\",\"function\":{\"name\":\"search\",\"description\":\"d\","
                + "\"parameters\":{\"type\":\"object\",\"properties\":{}}}}]}";

        var request = ReplayRequestCodec.parse(json);

        assertEquals(2, request.messages.size());
        assertEquals(1, request.messages.getFirst().toolCalls.size());
        assertEquals("search", request.tools.getFirst().function.name);
        assertEquals("result", request.messages.get(1).getTextContent());
    }

    @Test
    void parseRejectsMalformedJson() {
        assertThrows(RuntimeException.class, () -> ReplayRequestCodec.parse("not json"));
    }

    @Test
    void normalizeWrapsStringContent() {
        var normalized = ReplayRequestCodec.normalize("{\"messages\":[{\"role\":\"user\",\"content\":\"hi\"}]}");

        assertEquals("{\"messages\":[{\"role\":\"user\",\"content\":[{\"type\":\"text\",\"text\":\"hi\"}]}]}", normalized);
    }
}
