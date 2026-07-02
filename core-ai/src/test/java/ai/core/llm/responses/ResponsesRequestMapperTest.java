package ai.core.llm.responses;

import ai.core.llm.domain.Content;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.responses.ResponsesRequest;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ResponsesRequestMapperTest {
    private final ResponsesRequestMapper mapper = new ResponsesRequestMapper();

    @Test
    void mapsSupportedRequestFields() {
        var request = JsonUtil.fromJson(ResponsesRequest.class, """
                {
                  "model": "openrouter/test-model",
                  "stream": true,
                  "instructions": "Be concise.",
                  "temperature": 0.2,
                  "top_p": 0.9,
                  "max_output_tokens": 128,
                  "parallel_tool_calls": false,
                  "tool_choice": "auto",
                  "text": {"format": {"type": "json_object"}},
                  "tools": [
                    {
                      "type": "function",
                      "name": "lookup",
                      "description": "Look up a record.",
                      "parameters": {"type": "object", "properties": {}}
                    }
                  ],
                  "input": [
                    {
                      "type": "message",
                      "role": "user",
                      "content": [
                        {"type": "input_text", "text": "Describe this."},
                        {"type": "input_image", "image_url": "data:image/png;base64,abc", "detail": "high"},
                        {"type": "input_file", "file_url": "https://example.test/a.pdf"}
                      ]
                    },
                    {"type": "function_call", "call_id": "call_1", "name": "lookup", "arguments": "{\\"id\\":1}"},
                    {"type": "function_call_output", "call_id": "call_1", "output": "{\\"name\\":\\"Ada\\"}"}
                  ]
                }
                """);

        var completion = mapper.map(request);

        assertEquals("openrouter/test-model", completion.model);
        assertEquals(0.2, completion.temperature);
        assertEquals(0.9, completion.topP);
        assertEquals(128, completion.maxCompletionTokens);
        assertEquals(false, completion.parallelToolCalls);
        assertEquals("auto", completion.toolChoice);
        assertEquals("json_object", completion.responseFormat.type);
        assertEquals(1, completion.tools.size());
        assertEquals("lookup", completion.tools.getFirst().function.name);

        assertEquals(4, completion.messages.size());
        assertEquals(RoleType.SYSTEM, completion.messages.get(0).role);
        assertEquals("Be concise.", completion.messages.get(0).getTextContent());
        assertEquals(RoleType.USER, completion.messages.get(1).role);
        assertEquals(3, completion.messages.get(1).content.size());
        assertEquals(Content.ContentType.IMAGE_URL, completion.messages.get(1).content.get(1).type);
        assertEquals("high", completion.messages.get(1).content.get(1).imageUrl.detail);
        assertEquals(Content.ContentType.FILE, completion.messages.get(1).content.get(2).type);
        assertEquals("https://example.test/a.pdf", completion.messages.get(1).content.get(2).file.fileId);
        assertEquals(RoleType.ASSISTANT, completion.messages.get(2).role);
        assertEquals("call_1", completion.messages.get(2).toolCalls.getFirst().id);
        assertEquals(RoleType.TOOL, completion.messages.get(3).role);
        assertEquals("call_1", completion.messages.get(3).toolCallId);
    }

    @Test
    void mapsPlainTextFormatToNoChatResponseFormat() {
        var request = JsonUtil.fromJson(ResponsesRequest.class, """
                {"model":"m","stream":true,"text":{"format":{"type":"text"}},"input":"hello"}
                """);

        var completion = mapper.map(request);

        assertNull(completion.responseFormat);
        assertEquals("hello", completion.messages.getFirst().getTextContent());
    }

    @Test
    void rejectsStatefulFieldsBeforeMapping() {
        var request = JsonUtil.fromJson(ResponsesRequest.class, """
                {"model":"m","stream":true,"previous_response_id":"resp_1","input":"hello"}
                """);

        var error = assertThrows(ResponsesValidationException.class, () -> mapper.map(request));

        assertNotNull(error.getMessage());
    }

    @Test
    void rejectsBuiltInTools() {
        var request = JsonUtil.fromJson(ResponsesRequest.class, """
                {"model":"m","stream":true,"input":"hello","tools":[{"type":"web_search_preview"}]}
                """);

        assertThrows(ResponsesValidationException.class, () -> mapper.map(request));
    }
}
