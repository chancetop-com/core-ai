package ai.core.llm.providers;

import ai.core.llm.domain.AssistantMessage;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Usage;
import ai.core.llm.streaming.StreamingCallback;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class LiteLLMResponsesStreamState {
    static List<String> responsePayloads(String data) {
        if (data == null) return List.of();
        var trimmed = data.trim();
        if (trimmed.isEmpty() || "[DONE]".equals(trimmed)) return List.of();
        if (trimmed.startsWith("{")) return List.of(trimmed);

        var payloads = new ArrayList<String>();
        for (var line : trimmed.split("\\R")) {
            var lineTrimmed = line.trim();
            if (lineTrimmed.startsWith("data:")) {
                var payload = lineTrimmed.substring("data:".length()).trim();
                if (!payload.isEmpty() && !"[DONE]".equals(payload)) payloads.add(payload);
            }
        }
        return payloads;
    }

    private final CompletionResponse response;
    private final Choice choice;
    private final AssistantMessage message;
    private final StringBuilder content = new StringBuilder();
    private final StringBuilder reasoningContent = new StringBuilder();
    private final Map<Integer, ToolState> toolsByOutputIndex = new LinkedHashMap<>();
    private final Map<String, ToolState> toolsByItemId = new LinkedHashMap<>();
    private boolean roleEmitted;

    LiteLLMResponsesStreamState() {
        message = new AssistantMessage();
        message.role = RoleType.ASSISTANT;
        message.content = "";
        message.reasoningContent = "";
        message.toolCalls = new ArrayList<>();

        choice = new Choice();
        choice.index = 0;
        choice.message = message;
        response = CompletionResponse.of(List.of(choice), null);
    }

    void accept(Map<?, ?> rawEvent, StreamingCallback callback) {
        var event = LiteLLMResponsesUtil.asStringObjectMap(rawEvent);
        var type = LiteLLMResponsesUtil.string(event.get("type"));
        switch (type) {
            case "response.output_text.delta" -> onTextDelta(LiteLLMResponsesUtil.string(event.get("delta")), callback);
            case "response.output_item.added" -> onOutputItemAdded(eventItem(event), eventOutputIndex(event), callback);
            case "response.function_call_arguments.delta" -> onFunctionArgumentsDelta(event, callback);
            case "response.function_call_arguments.done" -> onFunctionArgumentsDone(event);
            case "response.output_item.done" -> onOutputItemDone(eventItem(event), eventOutputIndex(event));
            case "response.completed" -> onCompleted(responseEvent(event), callback);
            case "response.incomplete" -> finish(FinishReason.LENGTH, callback);
            case "response.failed" -> finish(FinishReason.STOP, callback);
            case null, default -> {
                if (isReasoningDelta(type)) onReasoningDelta(LiteLLMResponsesUtil.string(event.get("delta")), callback);
            }
        }
    }

    CompletionResponse response() {
        finalizeResponse();
        return response;
    }

    void finalizeResponse() {
        message.content = content.toString();
        message.reasoningContent = reasoningContent.toString();
        toolsByOutputIndex.values().stream()
                .sorted(Comparator.comparingInt(state -> state.toolIndex))
                .forEach(ToolState::syncFunctionCall);
        if (choice.finishReason == null) {
            choice.finishReason = message.toolCalls.isEmpty() ? FinishReason.STOP : FinishReason.TOOL_CALLS;
        }
    }

    private Map<String, Object> eventItem(Map<String, Object> event) {
        if (!(event.get("item") instanceof Map<?, ?> item)) return null;
        return LiteLLMResponsesUtil.asStringObjectMap(item);
    }

    private Map<String, Object> responseEvent(Map<String, Object> event) {
        if (!(event.get("response") instanceof Map<?, ?> responseMap)) return null;
        return LiteLLMResponsesUtil.asStringObjectMap(responseMap);
    }

    private Integer eventOutputIndex(Map<String, Object> event) {
        return LiteLLMResponsesUtil.intValue(event.get("output_index"));
    }

    private void onTextDelta(String delta, StreamingCallback callback) {
        if (delta == null) return;
        content.append(delta);
        emitContentDelta(delta, callback);
        callback.onChunk(delta);
    }

    private void onReasoningDelta(String delta, StreamingCallback callback) {
        if (delta == null) return;
        reasoningContent.append(delta);
        emitReasoningDelta(delta, callback);
        callback.onReasoningChunk(delta);
    }

    private void onOutputItemAdded(Map<String, Object> item, Integer outputIndex, StreamingCallback callback) {
        if (item == null || !"function_call".equals(LiteLLMResponsesUtil.string(item.get("type")))) return;
        var state = toolState(outputIndex, LiteLLMResponsesUtil.string(item.get("id")));
        state.itemId = LiteLLMResponsesUtil.firstNonNull(LiteLLMResponsesUtil.string(item.get("id")), state.itemId);
        state.callId = LiteLLMResponsesUtil.firstNonNull(LiteLLMResponsesUtil.string(item.get("call_id")), state.callId);
        state.name = LiteLLMResponsesUtil.firstNonNull(LiteLLMResponsesUtil.string(item.get("name")), state.name);
        toolsByItemId.putIfAbsent(state.itemId, state);
        state.syncFunctionCall();
        emitToolDelta(state.toolIndex, state.callId, state.name, "", callback);
    }

    private void onFunctionArgumentsDelta(Map<String, Object> event, StreamingCallback callback) {
        var delta = LiteLLMResponsesUtil.string(event.get("delta"));
        if (delta == null) return;
        var state = toolState(eventOutputIndex(event), LiteLLMResponsesUtil.string(event.get("item_id")));
        state.arguments.append(delta);
        state.syncFunctionCall();
        emitToolDelta(state.toolIndex, null, null, delta, callback);
    }

    private void onFunctionArgumentsDone(Map<String, Object> event) {
        var state = findToolState(eventOutputIndex(event), LiteLLMResponsesUtil.string(event.get("item_id")));
        if (state == null) return;
        var arguments = LiteLLMResponsesUtil.string(event.get("arguments"));
        if (arguments == null) return;
        state.arguments.setLength(0);
        state.arguments.append(arguments);
        state.syncFunctionCall();
    }

    private void onOutputItemDone(Map<String, Object> item, Integer outputIndex) {
        if (item == null) return;
        var type = LiteLLMResponsesUtil.string(item.get("type"));
        if ("function_call".equals(type)) {
            updateToolFromDoneItem(item, outputIndex);
        } else if ("message".equals(type)) {
            setFinalTextFromMessageItem(item);
        }
    }

    private void updateToolFromDoneItem(Map<String, Object> item, Integer outputIndex) {
        var state = toolState(outputIndex, LiteLLMResponsesUtil.string(item.get("id")));
        state.itemId = LiteLLMResponsesUtil.firstNonNull(LiteLLMResponsesUtil.string(item.get("id")), state.itemId);
        state.callId = LiteLLMResponsesUtil.firstNonNull(LiteLLMResponsesUtil.string(item.get("call_id")), state.callId);
        state.name = LiteLLMResponsesUtil.firstNonNull(LiteLLMResponsesUtil.string(item.get("name")), state.name);
        toolsByItemId.putIfAbsent(state.itemId, state);
        var arguments = LiteLLMResponsesUtil.string(item.get("arguments"));
        if (arguments != null) {
            state.arguments.setLength(0);
            state.arguments.append(arguments);
        }
        state.syncFunctionCall();
    }

    private void onCompleted(Map<String, Object> completedResponse, StreamingCallback callback) {
        if (completedResponse == null) return;
        if (completedResponse.get("output") instanceof List<?> output) {
            for (int i = 0; i < output.size(); i++) {
                applyCompletedOutputItem(output.get(i), i);
            }
        }
        if (completedResponse.get("usage") instanceof Map<?, ?> usageMap) {
            response.usage = mapUsage(LiteLLMResponsesUtil.asStringObjectMap(usageMap));
        }
        choice.finishReason = finishReason(completedResponse);
        emitTerminal(callback);
    }

    private void applyCompletedOutputItem(Object item, int outputIndex) {
        if (!(item instanceof Map<?, ?> rawItem)) return;
        var mappedItem = LiteLLMResponsesUtil.asStringObjectMap(rawItem);
        if ("function_call".equals(LiteLLMResponsesUtil.string(mappedItem.get("type")))) {
            onOutputItemDone(mappedItem, outputIndex);
            return;
        }
        if ("message".equals(LiteLLMResponsesUtil.string(mappedItem.get("type")))) {
            setFinalTextFromMessageItem(mappedItem);
        }
    }

    private void finish(FinishReason finishReason, StreamingCallback callback) {
        choice.finishReason = finishReason;
        emitTerminal(callback);
    }

    private void setFinalTextFromMessageItem(Map<String, Object> item) {
        var text = outputText(item);
        if (text == null) return;
        content.setLength(0);
        content.append(text);
    }

    private ToolState toolState(Integer outputIndex, String itemId) {
        var existing = findToolState(outputIndex, itemId);
        if (existing != null) return existing;

        var state = new ToolState();
        state.outputIndex = outputIndex == null ? toolsByOutputIndex.size() : outputIndex;
        state.toolIndex = message.toolCalls.size();
        state.itemId = itemId == null ? "fc_" + state.toolIndex : itemId;
        state.functionCall = FunctionCall.of(null, "function", null, "");
        state.functionCall.index = state.toolIndex;
        message.toolCalls.add(state.functionCall);
        toolsByOutputIndex.put(state.outputIndex, state);
        toolsByItemId.put(state.itemId, state);
        return state;
    }

    private ToolState findToolState(Integer outputIndex, String itemId) {
        if (itemId != null) {
            ToolState result = toolsByItemId.get(itemId);
            if (result != null) return result;
        }
        if (outputIndex != null) return toolsByOutputIndex.get(outputIndex);
        return null;
    }

    private void emitContentDelta(String delta, StreamingCallback callback) {
        var deltaMap = deltaMap();
        deltaMap.put("content", delta);
        emitChatDelta(deltaMap, null, null, callback);
    }

    private void emitReasoningDelta(String delta, StreamingCallback callback) {
        var deltaMap = deltaMap();
        deltaMap.put("reasoning_content", delta);
        emitChatDelta(deltaMap, null, null, callback);
    }

    private void emitToolDelta(int index, String id, String name, String arguments, StreamingCallback callback) {
        var function = new LinkedHashMap<String, Object>();
        LiteLLMResponsesUtil.putIfNotNull(function, "name", name);
        LiteLLMResponsesUtil.putIfNotNull(function, "arguments", arguments);
        var toolCall = new LinkedHashMap<String, Object>();
        toolCall.put("index", index);
        LiteLLMResponsesUtil.putIfNotNull(toolCall, "id", id);
        toolCall.put("type", "function");
        toolCall.put("function", function);

        var deltaMap = deltaMap();
        deltaMap.put("tool_calls", List.of(toolCall));
        emitChatDelta(deltaMap, null, null, callback);

        var callbackToolCall = FunctionCall.of(id, "function", name, arguments);
        callbackToolCall.index = index;
        callback.onTool(List.of(callbackToolCall));
    }

    private void emitTerminal(StreamingCallback callback) {
        finalizeResponse();
        var deltaMap = new LinkedHashMap<String, Object>();
        deltaMap.put("content", "");
        emitChatDelta(deltaMap, choice.finishReason, response.usage, callback);
    }

    private Map<String, Object> deltaMap() {
        var delta = new LinkedHashMap<String, Object>();
        if (!roleEmitted) {
            delta.put("role", "assistant");
            roleEmitted = true;
        }
        return delta;
    }

    private void emitChatDelta(Map<String, Object> delta, FinishReason finishReason, Usage usage, StreamingCallback callback) {
        var choiceMap = new LinkedHashMap<String, Object>();
        choiceMap.put("index", 0);
        choiceMap.put("delta", delta);
        if (finishReason != null) choiceMap.put("finish_reason", LiteLLMResponsesUtil.finishReasonValue(finishReason));

        var chunk = new LinkedHashMap<String, Object>();
        chunk.put("choices", List.of(choiceMap));
        if (usage != null) chunk.put("usage", JsonUtil.toMap(usage));
        callback.onRawData(JsonUtil.toJson(chunk));
    }

    private boolean isReasoningDelta(String type) {
        return type != null && type.startsWith("response.") && type.contains("reasoning") && type.endsWith(".delta");
    }

    private FinishReason finishReason(Map<String, Object> completedResponse) {
        if (hasFunctionCalls(completedResponse)) return FinishReason.TOOL_CALLS;
        var status = LiteLLMResponsesUtil.string(completedResponse.get("status"));
        if ("incomplete".equals(status)) return FinishReason.LENGTH;
        return FinishReason.STOP;
    }

    private boolean hasFunctionCalls(Map<String, Object> completedResponse) {
        if (!(completedResponse.get("output") instanceof List<?> output)) return !message.toolCalls.isEmpty();
        return output.stream().anyMatch(this::isFunctionCallItem);
    }

    private boolean isFunctionCallItem(Object item) {
        if (!(item instanceof Map<?, ?> rawItem)) return false;
        var itemMap = LiteLLMResponsesUtil.asStringObjectMap(rawItem);
        return "function_call".equals(LiteLLMResponsesUtil.string(itemMap.get("type")));
    }

    private String outputText(Map<String, Object> item) {
        if (!(item.get("content") instanceof List<?> content)) return null;
        var text = new StringBuilder();
        for (var part : content) {
            appendOutputText(text, part);
        }
        return text.toString();
    }

    private void appendOutputText(StringBuilder text, Object part) {
        if (!(part instanceof Map<?, ?> rawPart)) return;
        var partMap = LiteLLMResponsesUtil.asStringObjectMap(rawPart);
        if (!"output_text".equals(LiteLLMResponsesUtil.string(partMap.get("type")))) return;
        var partText = LiteLLMResponsesUtil.string(partMap.get("text"));
        if (partText != null) text.append(partText);
    }

    private Usage mapUsage(Map<String, Object> usageMap) {
        var usage = new Usage(
                LiteLLMResponsesUtil.intValue(usageMap.get("input_tokens"), 0),
                LiteLLMResponsesUtil.intValue(usageMap.get("output_tokens"), 0),
                LiteLLMResponsesUtil.intValue(usageMap.get("total_tokens"), 0));
        if (usageMap.get("input_tokens_details") instanceof Map<?, ?> promptDetailsMap) {
            var promptDetails = new Usage.PromptTokensDetails();
            promptDetails.cachedTokens = LiteLLMResponsesUtil.intValue(
                    LiteLLMResponsesUtil.asStringObjectMap(promptDetailsMap).get("cached_tokens"), 0);
            usage.setPromptTokensDetails(promptDetails);
        }
        if (usageMap.get("output_tokens_details") instanceof Map<?, ?> completionDetailsMap) {
            var completionDetails = new Usage.CompletionTokensDetails();
            completionDetails.reasoningTokens = LiteLLMResponsesUtil.intValue(
                    LiteLLMResponsesUtil.asStringObjectMap(completionDetailsMap).get("reasoning_tokens"), 0);
            usage.setCompletionTokensDetails(completionDetails);
        }
        return usage;
    }

    private static final class ToolState {
        int outputIndex;
        int toolIndex;
        String itemId;
        String callId;
        String name;
        final StringBuilder arguments = new StringBuilder();
        FunctionCall functionCall;

        void syncFunctionCall() {
            functionCall.id = callId;
            functionCall.type = "function";
            functionCall.index = toolIndex;
            if (functionCall.function == null) {
                functionCall.function = new FunctionCall.Function();
            }
            functionCall.function.name = name;
            functionCall.function.arguments = arguments.toString();
        }
    }
}
