package ai.core.llm.providers;

import ai.core.llm.domain.AssistantMessage;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FunctionCall;
import ai.core.document.Embedding;
import ai.core.llm.domain.Usage;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * @author stephen
 */
class LiteLLMProviderTest {

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
        choice.delta.toolCalls = new ArrayList<>();

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
