package ai.core.llm.providers;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.Content;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.Function;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.ReasoningEffort;
import ai.core.llm.domain.ResponseFormat;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.ToolType;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LiteLLMResponsesBridgeTest {
    @Test
    @SuppressWarnings("unchecked")
    void mapsChatRequestToResponsesRequest() {
        var request = CompletionRequest.of(
                List.of(
                        Message.of(RoleType.SYSTEM, "Be concise."),
                        Message.of(RoleType.USER, "Find this file", null, null, null),
                        Message.of(RoleType.TOOL, "tool result", null, "call_1", null)
                ),
                List.of(tool()),
                1.0,
                "azure/responses/gpt-5-mini",
                "test");
        request.topP = 0.9;
        request.maxCompletionTokens = 128;
        request.parallelToolCalls = false;
        request.toolChoice = "auto";
        request.responseFormat = responseFormat();
        request.reasoningEffort = ReasoningEffort.LOW;
        request.messages.get(1).content = List.of(Content.of("Find this file"), Content.ofFileUrl("https://example.test/a.pdf"));

        var body = LiteLLMResponsesBridge.toResponsesBody(request);

        assertEquals("azure/gpt-5-mini", body.get("model"));
        assertEquals(true, body.get("stream"));
        assertEquals("Be concise.", body.get("instructions"));
        assertEquals(0.9, body.get("top_p"));
        assertEquals(128, body.get("max_output_tokens"));
        assertEquals(false, body.get("parallel_tool_calls"));
        assertEquals("auto", body.get("tool_choice"));

        var input = (List<Map<String, Object>>) body.get("input");
        assertEquals(2, input.size());
        assertEquals("message", input.get(0).get("type"));
        assertEquals("user", input.get(0).get("role"));
        var userContent = (List<Map<String, Object>>) input.get(0).get("content");
        assertEquals("input_file", userContent.get(1).get("type"));
        assertEquals("https://example.test/a.pdf", userContent.get(1).get("file_url"));
        assertEquals("function_call_output", input.get(1).get("type"));
        assertEquals("call_1", input.get(1).get("call_id"));

        var tools = (List<Map<String, Object>>) body.get("tools");
        assertEquals("lookup", tools.getFirst().get("name"));
        assertEquals(true, tools.getFirst().get("strict"));

        var text = (Map<String, Object>) body.get("text");
        var format = (Map<String, Object>) text.get("format");
        assertEquals("json_schema", format.get("type"));
        assertEquals("LookupResult", format.get("name"));
        assertEquals(true, format.get("strict"));

        var reasoning = (Map<String, Object>) body.get("reasoning");
        assertEquals("low", reasoning.get("effort"));
    }

    @Test
    void translatesTextStreamToChatResponse() {
        var state = new LiteLLMResponsesStreamState();
        var callback = new CapturingCallback();

        state.accept(event("""
                {"type":"response.output_text.delta","delta":"Hel"}
                """), callback);
        state.accept(event("""
                {"type":"response.output_text.delta","delta":"lo"}
                """), callback);
        state.accept(event("""
                {
                  "type": "response.completed",
                  "response": {
                    "status": "completed",
                    "output": [
                      {"type":"message","content":[{"type":"output_text","text":"Hello"}]}
                    ],
                    "usage": {
                      "input_tokens": 2,
                      "output_tokens": 3,
                      "total_tokens": 5,
                      "input_tokens_details": {"cached_tokens": 1},
                      "output_tokens_details": {"reasoning_tokens": 1}
                    }
                  }
                }
                """), callback);

        var response = state.response();

        assertEquals("Hello", response.choices.getFirst().message.content);
        assertEquals(FinishReason.STOP, response.choices.getFirst().finishReason);
        assertEquals(2, response.usage.getPromptTokens());
        assertEquals(3, response.usage.getCompletionTokens());
        assertEquals(5, response.usage.getTotalTokens());
        assertEquals(1, response.usage.getPromptTokensDetails().cachedTokens);
        assertEquals(1, response.usage.getCompletionTokensDetails().reasoningTokens);
        assertEquals(List.of("Hel", "lo"), callback.chunks);
        assertTrue(callback.rawData.getFirst().contains("\"content\":\"Hel\""));
        assertTrue(callback.rawData.getLast().contains("\"finish_reason\":\"stop\""));
    }

    @Test
    void translatesFunctionCallStreamAndFinishesOnCompletedEvent() {
        var state = new LiteLLMResponsesStreamState();
        var callback = new CapturingCallback();

        state.accept(event("""
                {
                  "type": "response.output_item.added",
                  "output_index": 1,
                  "item": {"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup"}
                }
                """), callback);
        state.accept(event("""
                {"type":"response.function_call_arguments.delta","output_index":1,"item_id":"fc_1","delta":"{\\"id\\":"}
                """), callback);
        state.accept(event("""
                {
                  "type": "response.output_item.done",
                  "output_index": 1,
                  "item": {"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup","arguments":"{\\"id\\":1}"}
                }
                """), callback);

        assertFalse(callback.rawData.stream().anyMatch(raw -> raw.contains("\"finish_reason\"")));

        state.accept(event("""
                {
                  "type": "response.completed",
                  "response": {
                    "status": "completed",
                    "output": [
                      {"type":"function_call","id":"fc_1","call_id":"call_1","name":"lookup","arguments":"{\\"id\\":1}"}
                    ]
                  }
                }
                """), callback);

        var response = state.response();
        var toolCall = response.choices.getFirst().message.toolCalls.getFirst();

        assertEquals(FinishReason.TOOL_CALLS, response.choices.getFirst().finishReason);
        assertEquals("call_1", toolCall.id);
        assertEquals("lookup", toolCall.function.name);
        assertEquals("{\"id\":1}", toolCall.function.arguments);
        assertEquals(2, callback.toolDeltas.size());
        assertEquals("call_1", callback.toolDeltas.getFirst().getFirst().id);
        assertEquals("{\"id\":", callback.toolDeltas.get(1).getFirst().function.arguments);
        assertTrue(callback.rawData.getLast().contains("\"finish_reason\":\"tool_calls\""));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> event(String json) {
        return JsonUtil.fromJson(Map.class, json);
    }

    private Tool tool() {
        var tool = new Tool();
        tool.type = ToolType.FUNCTION;
        tool.function = new Function();
        tool.function.name = "lookup";
        tool.function.description = "Look up a record.";
        tool.function.parameters = objectSchema();
        tool.function.strict = true;
        return tool;
    }

    private JsonSchema objectSchema() {
        var schema = new JsonSchema();
        schema.type = JsonSchema.PropertyType.OBJECT;
        schema.properties = new LinkedHashMap<>();
        return schema;
    }

    private ResponseFormat responseFormat() {
        var responseFormat = new ResponseFormat();
        var schema = new ResponseFormat.JsonSchemaDefinition();
        schema.name = "LookupResult";
        schema.strict = true;
        schema.schema = objectSchema();
        responseFormat.jsonSchema = schema;
        return responseFormat;
    }

    private static final class CapturingCallback implements StreamingCallback {
        final List<String> chunks = new ArrayList<>();
        final List<String> rawData = new ArrayList<>();
        final List<List<FunctionCall>> toolDeltas = new ArrayList<>();

        @Override
        public void onChunk(String chunk) {
            chunks.add(chunk);
        }

        @Override
        public void onTool(List<FunctionCall> functionCalls) {
            toolDeltas.add(functionCalls);
        }

        @Override
        public void onRawData(String sseData) {
            rawData.add(sseData);
        }
    }
}
