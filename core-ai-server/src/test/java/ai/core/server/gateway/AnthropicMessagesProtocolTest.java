package ai.core.server.gateway;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AnthropicMessagesProtocolTest {
    @Test
    @SuppressWarnings("unchecked")
    void convertsMessagesRequestToChatCompletions() {
        var body = Map.of(
                "model", "claude-test",
                "system", List.of(Map.of("type", "text", "text", "be nice")),
                "max_tokens", 1024,
                "stop_sequences", List.of("END"),
                "temperature", 0.3,
                "metadata", Map.of("user_id", "user-1"),
                "tools", List.of(Map.of(
                        "name", "read_file",
                        "description", "read a file",
                        "input_schema", Map.of("type", "object", "properties", Map.of("path", Map.of("type", "string"))))),
                "tool_choice", Map.of("type", "tool", "name", "read_file"),
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", "find the file"))),
                        Map.of("role", "assistant", "content", List.of(
                                Map.of("type", "text", "text", "reading"),
                                Map.of("type", "tool_use", "id", "toolu_1", "name", "read_file", "input", Map.of("path", "a.txt")))),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "tool_result", "tool_use_id", "toolu_1", "content", "file content"),
                                Map.of("type", "text", "text", "now summarize"))),
                        Map.of("role", "user", "content", List.of(
                                Map.of("type", "image", "source", Map.of("type", "base64", "media_type", "image/png", "data", "abc")))))
        );

        var chat = AnthropicMessagesProtocol.toChatRequest(body, "upstream-model");

        assertEquals("upstream-model", chat.get("model"));
        assertEquals(1024, ((Number) chat.get("max_tokens")).intValue());
        assertEquals(0.3, ((Number) chat.get("temperature")).doubleValue(), 1e-9);
        assertEquals(List.of("END"), chat.get("stop"));
        assertEquals("user-1", chat.get("user"));
        assertEquals("read_file", ((Map<String, Object>) ((Map<String, Object>) chat.get("tool_choice")).get("function")).get("name"));
        var tools = (List<Map<String, Object>>) chat.get("tools");
        assertEquals(1, tools.size());
        assertEquals("read_file", ((Map<String, Object>) tools.getFirst().get("function")).get("name"));

        var messages = (List<Map<String, Object>>) chat.get("messages");
        assertEquals(6, messages.size());
        assertEquals("system", messages.get(0).get("role"));
        assertEquals("be nice", messages.get(0).get("content"));
        assertEquals("user", messages.get(1).get("role"));
        assertEquals("find the file", messages.get(1).get("content"));

        assertEquals("assistant", messages.get(2).get("role"));
        assertEquals("reading", messages.get(2).get("content"));
        var toolCalls = (List<Map<String, Object>>) messages.get(2).get("tool_calls");
        assertEquals("toolu_1", toolCalls.getFirst().get("id"));
        assertEquals("{\"path\":\"a.txt\"}", ((Map<String, Object>) toolCalls.getFirst().get("function")).get("arguments"));

        assertEquals("tool", messages.get(3).get("role"));
        assertEquals("toolu_1", messages.get(3).get("tool_call_id"));
        assertEquals("file content", messages.get(3).get("content"));
        assertEquals("user", messages.get(4).get("role"));
        assertEquals("now summarize", messages.get(4).get("content"));
        assertEquals("user", messages.get(5).get("role"));
        var imageParts = (List<Map<String, Object>>) messages.get(5).get("content");
        assertEquals("image_url", imageParts.getFirst().get("type"));
        assertEquals("data:image/png;base64,abc", ((Map<String, Object>) imageParts.getFirst().get("image_url")).get("url"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void convertsChatCompletionToMessageResponse() {
        var response = Map.of(
                "id", "chatcmpl-1",
                "choices", List.of(Map.of(
                        "index", 0,
                        "message", Map.of(
                                "role", "assistant",
                                "content", "let me read it",
                                "tool_calls", List.of(Map.of(
                                        "id", "call_1",
                                        "type", "function",
                                        "function", Map.of("name", "read_file", "arguments", "{\"path\":\"a.txt\"}")))),
                        "finish_reason", "tool_calls")),
                "usage", Map.of("prompt_tokens", 12, "completion_tokens", 34,
                        "prompt_tokens_details", Map.of("cached_tokens", 5)));

        var message = AnthropicMessagesProtocol.toMessageResponse(response, "claude-test");

        assertEquals("claude-test", message.get("model"));
        assertEquals("tool_use", message.get("stop_reason"));
        var content = (List<Map<String, Object>>) message.get("content");
        assertEquals(2, content.size());
        assertEquals("let me read it", content.get(0).get("text"));
        assertEquals("tool_use", content.get(1).get("type"));
        assertEquals(Map.of("path", "a.txt"), content.get(1).get("input"));
        var usage = (Map<String, Object>) message.get("usage");
        assertEquals(12, ((Number) usage.get("input_tokens")).intValue());
        assertEquals(34, ((Number) usage.get("output_tokens")).intValue());
        assertEquals(5, ((Number) usage.get("cache_read_input_tokens")).intValue());
    }

    @Test
    void estimatesTokensFromSystemMessagesAndTools() {
        var body = Map.of(
                "system", "be nice",
                "messages", List.of(
                        Map.of("role", "user", "content", List.of(Map.of("type", "text", "text", "hello world"))),
                        Map.of("role", "assistant", "content", List.of(
                                Map.of("type", "tool_use", "id", "toolu_1", "name", "read_file", "input", Map.of("path", "a.txt"))))),
                "tools", List.of(Map.of("name", "read_file", "description", "read a file",
                        "input_schema", Map.of("type", "object"))));

        assertTrue(AnthropicTokenEstimator.estimate(body) > 10);
        assertEquals(1, AnthropicTokenEstimator.estimate(Map.of("messages", List.of())));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsErrorBody() {
        var error = AnthropicMessagesProtocol.toErrorResponse(401,
                Map.of("error", Map.of("message", "bad key", "type", "invalid_api_key")));

        assertEquals("error", error.get("type"));
        assertEquals("bad key", ((Map<String, Object>) error.get("error")).get("message"));
        assertEquals("authentication_error", ((Map<String, Object>) error.get("error")).get("type"));
    }

    @Test
    void translatesChatStreamToMessagesEvents() {
        var translator = new AnthropicMessagesStream("claude-test");
        var events = new ArrayList<AnthropicMessagesStream.SseEvent>();

        events.addAll(translator.onEvent(null,
                "{\"id\":\"chatcmpl-1\",\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}"));
        events.addAll(translator.onEvent(null,
                "{\"choices\":[{\"index\":0,\"delta\":{\"content\":\"hello \"},\"finish_reason\":null}]}"));
        events.addAll(translator.onEvent(null,
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,\"id\":\"call_1\","
                        + "\"function\":{\"name\":\"read_file\",\"arguments\":\"{\\\"path\\\":\"}}]},\"finish_reason\":null}]}"));
        events.addAll(translator.onEvent(null,
                "{\"choices\":[{\"index\":0,\"delta\":{\"tool_calls\":[{\"index\":0,"
                        + "\"function\":{\"arguments\":\"\\\"a.txt\\\"}\"}}]},\"finish_reason\":\"tool_calls\"}]}"));
        events.addAll(translator.onEvent(null,
                "{\"choices\":[],\"usage\":{\"prompt_tokens\":12,\"completion_tokens\":34}}"));
        events.addAll(translator.onEvent(null, "[DONE]"));

        assertEquals(List.of("message_start", "content_block_start", "content_block_delta", "content_block_stop",
                        "content_block_start", "content_block_delta", "content_block_delta", "content_block_stop",
                        "message_delta", "message_stop"),
                events.stream().map(AnthropicMessagesStream.SseEvent::event).toList());
        assertTrue(events.get(0).data().contains("\"input_tokens\":0"));
        assertTrue(events.get(2).data().contains("hello "));
        assertTrue(events.get(4).data().contains("\"type\":\"tool_use\""));
        assertTrue(events.get(5).data().contains("\"type\":\"input_json_delta\""));
        assertTrue(events.get(5).data().contains("{\\\"path\\\":"));
        assertTrue(events.get(6).data().contains("\\\"a.txt\\\"}"));
        assertEquals("content_block_stop", events.get(7).event());
        assertTrue(events.get(8).data().contains("\"stop_reason\":\"tool_use\""));
        assertTrue(events.get(8).data().contains("\"output_tokens\":34"));
    }

    @Test
    void finishesEmptyStreamWithAValidMessage() {
        var translator = new AnthropicMessagesStream("claude-test");

        var events = translator.onEvent(null, "[DONE]");

        assertEquals(List.of("message_start", "message_delta", "message_stop"),
                events.stream().map(AnthropicMessagesStream.SseEvent::event).toList());
        assertTrue(events.get(1).data().contains("\"stop_reason\":\"end_turn\""));
    }
}

