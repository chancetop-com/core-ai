package ai.core.llm.responses;

import ai.core.llm.domain.AssistantMessage;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.domain.responses.ResponsesRequest;
import ai.core.utils.JsonUtil;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResponsesStreamSynthesizerTest {
    @Test
    void synthesizesTextEvents() {
        var request = request();
        var synthesizer = new ResponsesStreamSynthesizer(request);
        var events = new ArrayList<Event>();

        synthesizer.start((type, data) -> events.add(new Event(type, data)));
        synthesizer.accept("""
                {"choices":[{"index":0,"delta":{"role":"assistant","content":"Hel"}}]}
                """, (type, data) -> events.add(new Event(type, data)));
        synthesizer.accept("""
                {"choices":[{"index":0,"delta":{"content":"lo"},"finish_reason":"stop"}]}
                """, (type, data) -> events.add(new Event(type, data)));
        synthesizer.complete(textCompletion("Hello"), (type, data) -> events.add(new Event(type, data)));

        assertEquals(List.of(
                "response.created",
                "response.in_progress",
                "response.output_item.added",
                "response.content_part.added",
                "response.output_text.delta",
                "response.output_text.delta",
                "response.output_text.done",
                "response.content_part.done",
                "response.output_item.done",
                "response.completed"
        ), events.stream().map(Event::type).toList());
        assertSequenceNumbersIncrease(events);

        var completed = JsonUtil.toMap(events.getLast().data);
        var response = (Map<?, ?>) completed.get("response");
        assertEquals("completed", response.get("status"));
        var output = (List<?>) response.get("output");
        var message = (Map<?, ?>) output.getFirst();
        var content = (List<?>) message.get("content");
        assertEquals("Hello", ((Map<?, ?>) content.getFirst()).get("text"));
    }

    @Test
    void synthesizesFunctionCallEvents() {
        var request = request();
        var synthesizer = new ResponsesStreamSynthesizer(request);
        var events = new ArrayList<Event>();

        synthesizer.start((type, data) -> events.add(new Event(type, data)));
        synthesizer.accept("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","type":"function","function":{"name":"lookup","arguments":"{\\"id\\""}}]}}]}
                """, (type, data) -> events.add(new Event(type, data)));
        synthesizer.accept("""
                {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":":1}"}}]},"finish_reason":"tool_calls"}]}
                """, (type, data) -> events.add(new Event(type, data)));
        synthesizer.complete(toolCompletion(), (type, data) -> events.add(new Event(type, data)));

        var names = events.stream().map(Event::type).toList();
        assertTrue(names.contains("response.function_call_arguments.delta"));
        assertTrue(names.contains("response.function_call_arguments.done"));
        assertTrue(names.contains("response.output_item.done"));

        var completed = JsonUtil.toMap(events.getLast().data);
        var response = (Map<?, ?>) completed.get("response");
        var output = (List<?>) response.get("output");
        var functionCall = (Map<?, ?>) output.getFirst();
        assertEquals("function_call", functionCall.get("type"));
        assertEquals("lookup", functionCall.get("name"));
        assertEquals("{\"id\":1}", functionCall.get("arguments"));
    }

    private ResponsesRequest request() {
        return JsonUtil.fromJson(ResponsesRequest.class, """
                {"model":"m","stream":true,"input":"hello"}
                """);
    }

    private CompletionResponse textCompletion(String text) {
        var message = new AssistantMessage();
        message.role = RoleType.ASSISTANT;
        message.content = text;
        var choice = new Choice();
        choice.message = message;
        choice.finishReason = FinishReason.STOP;
        return CompletionResponse.of(List.of(choice), new Usage(2, 1, 3));
    }

    private CompletionResponse toolCompletion() {
        var message = new AssistantMessage();
        message.role = RoleType.ASSISTANT;
        message.content = "";
        message.toolCalls = List.of(FunctionCall.of("call_1", "function", "lookup", "{\"id\":1}"));
        var choice = new Choice();
        choice.message = message;
        choice.finishReason = FinishReason.TOOL_CALLS;
        return CompletionResponse.of(List.of(choice), new Usage(4, 2, 6));
    }

    private void assertSequenceNumbersIncrease(List<Event> events) {
        long previous = 0;
        for (var event : events) {
            var data = JsonUtil.toMap(event.data);
            var sequence = ((Number) data.get("sequence_number")).longValue();
            assertTrue(sequence > previous);
            previous = sequence;
        }
    }

    private record Event(String type, String data) {
    }
}
