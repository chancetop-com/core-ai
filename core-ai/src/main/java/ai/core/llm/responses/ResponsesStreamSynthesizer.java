package ai.core.llm.responses;

import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.responses.ResponsesRequest;
import ai.core.utils.JsonUtil;

import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public class ResponsesStreamSynthesizer {
    private final ResponsesRequest request;
    private final ResponsesResponseMapper mapper = new ResponsesResponseMapper();
    private final String responseId;
    private final long createdAt;
    private long sequenceNumber;
    private int nextOutputIndex;
    private String messageId;
    private Integer messageOutputIndex;
    private final StringBuilder text = new StringBuilder();
    private boolean messageAdded;
    private boolean contentPartAdded;
    private boolean messageDone;
    private final Map<Integer, ToolState> tools = new LinkedHashMap<>();

    public ResponsesStreamSynthesizer(ResponsesRequest request) {
        this.request = request;
        var suffix = UUID.randomUUID().toString().replace("-", "");
        this.responseId = "resp_" + suffix;
        this.createdAt = Instant.now().getEpochSecond();
    }

    public String responseId() {
        return responseId;
    }

    public long createdAt() {
        return createdAt;
    }

    public Map<Integer, String> functionItemIds() {
        var ids = new LinkedHashMap<Integer, String>();
        tools.forEach((index, state) -> ids.put(index, state.itemId));
        return ids;
    }

    public String messageId() {
        if (messageId == null) messageId = "msg_" + UUID.randomUUID().toString().replace("-", "");
        return messageId;
    }

    public void start(ResponsesEventListener listener) {
        sendResponseEvent("response.created", "in_progress", listener);
        sendResponseEvent("response.in_progress", "in_progress", listener);
    }

    public void accept(String sseData, ResponsesEventListener listener) {
        if (sseData == null || "[DONE]".equals(sseData)) return;
        var chunk = JsonUtil.fromJson(CompletionResponse.class, sseData);
        if (chunk.choices == null || chunk.choices.isEmpty()) return;
        var choice = chunk.choices.getFirst();
        if (choice.delta != null) {
            if (choice.delta.content != null && !choice.delta.content.isEmpty()) {
                handleTextDelta(choice.delta.content, listener);
            }
            if (choice.delta.toolCalls != null) {
                for (var toolCall : choice.delta.toolCalls) {
                    handleToolDelta(toolCall, listener);
                }
            }
        }
        if (choice.finishReason != null) {
            finishOpenItems(listener);
        }
    }

    public ai.core.llm.domain.responses.ResponsesResponse complete(CompletionResponse completion, ResponsesEventListener listener) {
        finishOpenItems(listener);
        var response = mapper.completed(completion, request, responseId, createdAt, messageId(), functionItemIds());
        send("response." + response.status, responseEvent("response." + response.status, response), listener);
        return response;
    }

    public ai.core.llm.domain.responses.ResponsesResponse fail(Throwable error, ResponsesEventListener listener) {
        var response = mapper.failed(request, responseId, createdAt, error);
        send("response.failed", responseEvent("response.failed", response), listener);
        return response;
    }

    private void handleTextDelta(String delta, ResponsesEventListener listener) {
        ensureMessageStarted(listener);
        text.append(delta);
        var event = event("response.output_text.delta");
        event.put("item_id", messageId());
        event.put("output_index", messageOutputIndex);
        event.put("content_index", 0);
        event.put("delta", delta);
        send("response.output_text.delta", event, listener);
    }

    private void ensureMessageStarted(ResponsesEventListener listener) {
        if (messageAdded) return;
        messageOutputIndex = nextOutputIndex++;
        var event = event("response.output_item.added");
        event.put("output_index", messageOutputIndex);
        event.put("item", mapper.messageItemInProgress(messageId()));
        send("response.output_item.added", event, listener);
        messageAdded = true;

        var partEvent = event("response.content_part.added");
        partEvent.put("item_id", messageId());
        partEvent.put("output_index", messageOutputIndex);
        partEvent.put("content_index", 0);
        partEvent.put("part", mapper.outputTextPart(""));
        send("response.content_part.added", partEvent, listener);
        contentPartAdded = true;
    }

    private void handleToolDelta(FunctionCall delta, ResponsesEventListener listener) {
        if (delta == null || delta.index == null) return;
        var state = tools.computeIfAbsent(delta.index, this::newToolState);
        if (delta.id != null) state.callId = delta.id;
        if (delta.function != null && delta.function.name != null) state.name = delta.function.name;
        ensureToolStarted(state, listener);
        if (delta.function != null && delta.function.arguments != null) {
            state.arguments.append(delta.function.arguments);
            var event = event("response.function_call_arguments.delta");
            event.put("item_id", state.itemId);
            event.put("output_index", state.outputIndex);
            event.put("delta", delta.function.arguments);
            send("response.function_call_arguments.delta", event, listener);
        }
    }

    private ToolState newToolState(int index) {
        var state = new ToolState();
        state.index = index;
        state.itemId = "fc_" + UUID.randomUUID().toString().replace("-", "");
        state.outputIndex = nextOutputIndex++;
        return state;
    }

    private void ensureToolStarted(ToolState state, ResponsesEventListener listener) {
        if (state.added) return;
        var event = event("response.output_item.added");
        event.put("output_index", state.outputIndex);
        event.put("item", functionItem(state, "in_progress"));
        send("response.output_item.added", event, listener);
        state.added = true;
    }

    private void finishOpenItems(ResponsesEventListener listener) {
        if (messageAdded && !messageDone) {
            if (contentPartAdded) {
                var textDone = event("response.output_text.done");
                textDone.put("item_id", messageId());
                textDone.put("output_index", messageOutputIndex);
                textDone.put("content_index", 0);
                textDone.put("text", text.toString());
                send("response.output_text.done", textDone, listener);

                var partDone = event("response.content_part.done");
                partDone.put("item_id", messageId());
                partDone.put("output_index", messageOutputIndex);
                partDone.put("content_index", 0);
                partDone.put("part", mapper.outputTextPart(text.toString()));
                send("response.content_part.done", partDone, listener);
            }
            var itemDone = event("response.output_item.done");
            itemDone.put("output_index", messageOutputIndex);
            itemDone.put("item", mapper.messageItem(messageId(), "completed", text.toString()));
            send("response.output_item.done", itemDone, listener);
            messageDone = true;
        }
        tools.values().stream()
                .sorted(Comparator.comparingInt(state -> state.outputIndex))
                .forEach(state -> finishTool(state, listener));
    }

    private void finishTool(ToolState state, ResponsesEventListener listener) {
        if (state.done) return;
        ensureToolStarted(state, listener);
        var argumentsDone = event("response.function_call_arguments.done");
        argumentsDone.put("item_id", state.itemId);
        argumentsDone.put("output_index", state.outputIndex);
        argumentsDone.put("arguments", state.arguments.toString());
        send("response.function_call_arguments.done", argumentsDone, listener);

        var itemDone = event("response.output_item.done");
        itemDone.put("output_index", state.outputIndex);
        itemDone.put("item", functionItem(state, "completed"));
        send("response.output_item.done", itemDone, listener);
        state.done = true;
    }

    private ai.core.llm.domain.responses.ResponsesOutputItem functionItem(ToolState state, String status) {
        var call = FunctionCall.of(state.callId, "function", state.name, state.arguments.toString());
        return mapper.functionItem(state.itemId, call, status);
    }

    private void sendResponseEvent(String type, String status, ResponsesEventListener listener) {
        send(type, responseEvent(type, mapper.base(request, responseId, createdAt, status)), listener);
    }

    private Map<String, Object> responseEvent(String type, Object response) {
        var event = event(type);
        event.put("response", response);
        return event;
    }

    private Map<String, Object> event(String type) {
        return mapper.event(type, ++sequenceNumber);
    }

    private void send(String type, Map<String, Object> event, ResponsesEventListener listener) {
        listener.onEvent(type, JsonUtil.toJson(event));
    }

    private static final class ToolState {
        int index;
        int outputIndex;
        String itemId;
        String callId;
        String name;
        final StringBuilder arguments = new StringBuilder();
        boolean added;
        boolean done;
    }
}
