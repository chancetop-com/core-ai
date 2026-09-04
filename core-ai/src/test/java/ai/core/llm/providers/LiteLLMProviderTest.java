package ai.core.llm.providers;

import ai.core.llm.domain.AssistantMessage;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.document.Embedding;
import ai.core.llm.domain.Usage;
import ai.core.utils.JsonUtil;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class LiteLLMProviderTest {

    @Test
    void applyGatewayHeadersPutsSessionAndAgentHeaders() {
        var request = CompletionRequest.of(List.of(), List.of(), null, "test-model", "menu-agent");
        request.setSessionId("conversation-1");
        var req = new HTTPRequest(HTTPMethod.POST, "http://localhost/chat/completions");

        LiteLLMResponsesBridge.applyGatewayHeaders(req, request);

        assertEquals("conversation-1", req.headers.get("x-session-id"));
        assertEquals("menu-agent", req.headers.get("x-agent-name"));
    }

    @Test
    void applyGatewayHeadersEncodesNonAsciiAgentName() {
        var request = CompletionRequest.of(List.of(), List.of(), null, "test-model", "Docs 构建修复助手");
        request.setSessionId("conversation-1");
        var req = new HTTPRequest(HTTPMethod.POST, "http://localhost/chat/completions");

        LiteLLMResponsesBridge.applyGatewayHeaders(req, request);

        var expected = "=?UTF-8?B?" + Base64.getEncoder().encodeToString("Docs 构建修复助手".getBytes(StandardCharsets.UTF_8)) + "?=";
        assertEquals(expected, req.headers.get("x-agent-name"));
        assertEquals("conversation-1", req.headers.get("x-session-id"));
        assertTrue(req.headers.get("x-agent-name").chars().allMatch(c -> c >= 0x20 && c <= 0x7E), "header value must stay ASCII");
    }

    @Test
    void applyGatewayHeadersSkipsBlankValues() {
        var request = CompletionRequest.of(List.of(), List.of(), null, "test-model", " ");
        request.setSessionId(" ");
        var req = new HTTPRequest(HTTPMethod.POST, "http://localhost/chat/completions");

        LiteLLMResponsesBridge.applyGatewayHeaders(req, request);

        assertNull(req.headers.get("x-session-id"));
        assertNull(req.headers.get("x-agent-name"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void sessionIdIsNotSerializedToRequestBody() {
        var request = CompletionRequest.of(List.of(), List.of(), null, "test-model", "test");
        request.setSessionId("conversation-1");

        var body = (Map<String, Object>) JsonUtil.toMap(request);

        assertFalse(body.containsKey("session_id"), "session id must stay out of the upstream request body");
    }

    @Test
    void testParseEmbeddingResponse() {
        var queries = List.of("Hello world", "How are you");

        // mock OpenAI embedding API response
        String mockResponse = """
                {
                    "model": "text-embedding-ada-002",
                    "data": [
                        {"index": 0, "embedding": [0.1, 0.2, 0.3]},
                        {"index": 1, "embedding": [0.4, 0.5, 0.6]}
                    ],
                    "usage": {"prompt_tokens": 5, "total_tokens": 5}
                }
                """;

        var response = parseEmbeddingResponse(queries, mockResponse);

        assertNotNull(response);
        assertNotNull(response.embeddings);
        assertEquals(2, response.embeddings.size());

        assertEquals("Hello world", response.embeddings.get(0).text);
        assertEquals(List.of(0.1, 0.2, 0.3), response.embeddings.get(0).embedding.vectors());

        assertEquals("How are you", response.embeddings.get(1).text);
        assertEquals(List.of(0.4, 0.5, 0.6), response.embeddings.get(1).embedding.vectors());

        assertNotNull(response.usage);
        assertEquals(5, response.usage.getPromptTokens());
        assertEquals(5, response.usage.getTotalTokens());
    }

    @SuppressWarnings("unchecked")
    private EmbeddingResponse parseEmbeddingResponse(List<String> queries, String responseText) {
        var responseMap = (Map<String, Object>) JsonUtil.fromJson(Map.class, responseText);
        var dataList = (List<Map<String, Object>>) responseMap.get("data");
        var usageMap = (Map<String, Object>) responseMap.get("usage");

        var embeddings = new ArrayList<EmbeddingResponse.EmbeddingData>();
        for (var data : dataList) {
            int index = ((Number) data.get("index")).intValue();
            var embeddingList = (List<Number>) data.get("embedding");
            var vectors = embeddingList.stream()
                    .map(Number::doubleValue)
                    .toList();
            var embedding = new Embedding(vectors);
            embeddings.add(EmbeddingResponse.EmbeddingData.of(queries.get(index), embedding));
        }

        var usage = new Usage(
                ((Number) usageMap.get("prompt_tokens")).intValue(),
                0,
                ((Number) usageMap.get("total_tokens")).intValue()
        );

        return EmbeddingResponse.of(embeddings, usage);
    }

    @Test
    void repairsInvalidJsonEscapesInsideStringValues() {
        String invalidJson = "{\"choices\":[{\"delta\":{\"content\":\"path \\{value\\} and \\q\"}}]}";

        String repaired = LiteLLMProvider.repairInvalidJsonEscapes(invalidJson);

        var response = JsonUtil.fromJson(CompletionResponse.class, repaired);
        assertEquals("path \\{value\\} and \\q", response.choices.getFirst().delta.content);
    }

    @Test
    void preservesValidJsonEscapes() {
        String json = "{\"content\":\"line\\nquote: \\\"\\\"\"}";

        String repaired = LiteLLMProvider.repairInvalidJsonEscapes(json);

        assertEquals(json, repaired);
    }

    @Test
    void parsesReasoningAliasFromStreamingDelta() {
        String json = "{\"choices\":[{\"delta\":{\"role\":\"assistant\",\"reasoning\":\"thinking step by step\"}}]}";

        var chunk = LiteLLMCompletionChunkParser.parse(json);

        assertEquals("thinking step by step", chunk.choices.getFirst().delta.reasoningContent);
    }

    @Test
    void normalizesStopToToolCallsWhenToolCallsWereCollected() {
        var choice = new Choice();
        choice.finishReason = FinishReason.STOP;
        choice.message = new AssistantMessage();
        choice.message.toolCalls = new ArrayList<>(List.of(new FunctionCall()));
        var response = CompletionResponse.of(List.of(choice), new Usage(1, 1, 2));

        LiteLLMResponsesBridge.normalizeFinishReason(response);

        assertEquals(FinishReason.TOOL_CALLS, response.choices.getFirst().finishReason);
    }

    @Test
    void keepsStopWhenNoToolCallsWereCollected() {
        var choice = new Choice();
        choice.finishReason = FinishReason.STOP;
        choice.message = new AssistantMessage();
        choice.message.toolCalls = new ArrayList<>();
        var response = CompletionResponse.of(List.of(choice), new Usage(1, 1, 2));

        LiteLLMResponsesBridge.normalizeFinishReason(response);

        assertEquals(FinishReason.STOP, response.choices.getFirst().finishReason);
    }

    @Test
    void keepsToolCallsFinishReasonUnchanged() {
        var choice = new Choice();
        choice.finishReason = FinishReason.TOOL_CALLS;
        choice.message = new AssistantMessage();
        choice.message.toolCalls = new ArrayList<>(List.of(new FunctionCall()));
        var response = CompletionResponse.of(List.of(choice), new Usage(1, 1, 2));

        LiteLLMResponsesBridge.normalizeFinishReason(response);

        assertEquals(FinishReason.TOOL_CALLS, response.choices.getFirst().finishReason);
    }

    @Test
    void parsesReasoningAliasFromNonStreamingMessage() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"reasoning\":\"analysis\",\"content\":\"answer\"}}]}";

        var chunk = LiteLLMCompletionChunkParser.parse(json);

        assertEquals("analysis", chunk.choices.getFirst().message.reasoningContent);
        assertEquals("answer", chunk.choices.getFirst().message.content);
    }

    @Test
    void reasoningContentKeyTakesPrecedenceOverReasoningAlias() {
        String json = "{\"choices\":[{\"delta\":{\"reasoning\":\"alias\",\"reasoning_content\":\"canonical\"}}]}";

        var chunk = LiteLLMCompletionChunkParser.parse(json);

        assertEquals("canonical", chunk.choices.getFirst().delta.reasoningContent);
    }

    @Test
    void objectValuedReasoningKeyIgnored() {
        String json = "{\"choices\":[{\"delta\":{\"reasoning\":{\"summary\":\"structured\"}}}]}";

        var chunk = LiteLLMCompletionChunkParser.parse(json);

        assertNull(chunk.choices.getFirst().delta.reasoningContent);
    }

    @Test
    void testParallelToolCallsMerge() {
        var finalChoice = createFinalChoice();

        // Chunk 1: tool_call[0] starts with id, type, name
        var chunk1 = createChunkChoice(0, "call_abc", "function", "get_weather", "");
        copyToolCallsToFinalChoice(finalChoice, chunk1);

        // Chunk 2: tool_call[0] arguments fragment
        var chunk2 = createChunkChoice(0, null, null, null, "{\"city\":");
        copyToolCallsToFinalChoice(finalChoice, chunk2);

        // Chunk 3: tool_call[0] arguments fragment + tool_call[1] starts
        var chunk3 = createChunkChoiceMultiple(
                new ToolCallDelta(0, null, null, null, "\"Beijing\"}"),
                new ToolCallDelta(1, "call_def", "function", "get_time", "")
        );
        copyToolCallsToFinalChoice(finalChoice, chunk3);

        // Chunk 4: tool_call[1] arguments
        var chunk4 = createChunkChoice(1, null, null, null, "{\"tz\":\"UTC\"}");
        copyToolCallsToFinalChoice(finalChoice, chunk4);

        // Verify results
        var toolCalls = finalChoice.message.toolCalls;
        assertNotNull(toolCalls);
        assertEquals(2, toolCalls.size());

        // Verify tool_call[0]
        var tc0 = toolCalls.get(0);
        assertEquals("call_abc", tc0.id);
        assertEquals("function", tc0.type);
        assertEquals("get_weather", tc0.function.name);
        assertEquals("{\"city\":\"Beijing\"}", tc0.function.arguments);

        // Verify tool_call[1]
        var tc1 = toolCalls.get(1);
        assertEquals("call_def", tc1.id);
        assertEquals("function", tc1.type);
        assertEquals("get_time", tc1.function.name);
        assertEquals("{\"tz\":\"UTC\"}", tc1.function.arguments);
    }

    @Test
    void testSingleToolCallMerge() {
        var finalChoice = createFinalChoice();

        // Chunk 1: tool call starts
        var chunk1 = createChunkChoice(0, "call_123", "function", "search", "");
        copyToolCallsToFinalChoice(finalChoice, chunk1);

        // Chunk 2-4: arguments fragments
        copyToolCallsToFinalChoice(finalChoice, createChunkChoice(0, null, null, null, "{\"q\":"));
        copyToolCallsToFinalChoice(finalChoice, createChunkChoice(0, null, null, null, "\"hello"));
        copyToolCallsToFinalChoice(finalChoice, createChunkChoice(0, null, null, null, " world\"}"));

        var toolCalls = finalChoice.message.toolCalls;
        assertEquals(1, toolCalls.size());

        var tc = toolCalls.get(0);
        assertEquals("call_123", tc.id);
        assertEquals("function", tc.type);
        assertEquals("search", tc.function.name);
        assertEquals("{\"q\":\"hello world\"}", tc.function.arguments);
    }

    @Test
    void testToolCallWithNullIndex() {
        var finalChoice = createFinalChoice();

        // Chunk with null index should be skipped
        var chunk = new Choice();
        chunk.delta = new AssistantMessage();
        chunk.delta.toolCalls = new ArrayList<>();
        var tc = new FunctionCall();
        tc.index = null;  // null index
        tc.id = "call_xyz";
        chunk.delta.toolCalls.add(tc);

        copyToolCallsToFinalChoice(finalChoice, chunk);

        // Should remain empty
        assertEquals(0, finalChoice.message.toolCalls.size());
    }

    @Test
    void testToolCallIdAndTypeUpdateInLaterChunk() {
        var finalChoice = createFinalChoice();

        // Chunk 1: only index and partial data
        var chunk1 = createChunkChoice(0, null, null, "my_func", "");
        copyToolCallsToFinalChoice(finalChoice, chunk1);

        // Chunk 2: id and type come later (unusual but should work)
        var chunk2 = createChunkChoice(0, "call_late", "function", null, "{\"a\":1}");
        copyToolCallsToFinalChoice(finalChoice, chunk2);

        var tc = finalChoice.message.toolCalls.get(0);
        assertEquals("call_late", tc.id);
        assertEquals("function", tc.type);
        assertEquals("my_func", tc.function.name);
        assertEquals("{\"a\":1}", tc.function.arguments);
    }

    private Choice createFinalChoice() {
        var choice = new Choice();
        choice.message = new AssistantMessage();
        choice.message.toolCalls = new ArrayList<>();
        return choice;
    }

    private Choice createChunkChoice(int index, String id, String type, String name, String arguments) {
        var choice = new Choice();
        choice.delta = new AssistantMessage();
        choice.delta.toolCalls = new ArrayList<>();

        var tc = new FunctionCall();
        tc.index = index;
        tc.id = id;
        tc.type = type;
        if (name != null || arguments != null) {
            tc.function = new FunctionCall.Function();
            tc.function.name = name;
            tc.function.arguments = arguments;
        }
        choice.delta.toolCalls.add(tc);

        return choice;
    }

    private Choice createChunkChoiceMultiple(ToolCallDelta... deltas) {
        var choice = new Choice();
        choice.delta = new AssistantMessage();
        choice.delta.toolCalls = new ArrayList<>(deltas.length);

        for (var d : deltas) {
            var tc = new FunctionCall();
            tc.index = d.index;
            tc.id = d.id;
            tc.type = d.type;
            if (d.name != null || d.arguments != null) {
                tc.function = new FunctionCall.Function();
                tc.function.name = d.name;
                tc.function.arguments = d.arguments;
            }
            choice.delta.toolCalls.add(tc);
        }

        return choice;
    }

    private void copyToolCallsToFinalChoice(Choice finalChoice, Choice chunkChoice) {
        if (finalChoice.message.toolCalls == null) {
            finalChoice.message.toolCalls = new ArrayList<>();
        }

        for (var deltaToolCall : chunkChoice.delta.toolCalls) {
            if (deltaToolCall.index == null) {
                continue;
            }

            while (finalChoice.message.toolCalls.size() <= deltaToolCall.index) {
                finalChoice.message.toolCalls.add(null);
            }

            var existingToolCall = finalChoice.message.toolCalls.get(deltaToolCall.index);
            if (existingToolCall == null) {
                existingToolCall = new FunctionCall();
                existingToolCall.function = new FunctionCall.Function();
                existingToolCall.function.arguments = "";
                finalChoice.message.toolCalls.set(deltaToolCall.index, existingToolCall);
            }

            if (deltaToolCall.id != null) {
                existingToolCall.id = deltaToolCall.id;
            }
            if (deltaToolCall.type != null) {
                existingToolCall.type = deltaToolCall.type;
            }
            if (deltaToolCall.function != null) {
                if (deltaToolCall.function.name != null) {
                    existingToolCall.function.name = deltaToolCall.function.name;
                }
                if (deltaToolCall.function.arguments != null) {
                    existingToolCall.function.arguments += deltaToolCall.function.arguments;
                }
            }
        }
    }

    private record ToolCallDelta(int index, String id, String type, String name, String arguments) {

    }
}
