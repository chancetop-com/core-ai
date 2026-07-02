package ai.core.llm.responses;

import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Usage;
import ai.core.llm.domain.responses.ResponsesContentPart;
import ai.core.llm.domain.responses.ResponsesError;
import ai.core.llm.domain.responses.ResponsesOutputItem;
import ai.core.llm.domain.responses.ResponsesReasoning;
import ai.core.llm.domain.responses.ResponsesRequest;
import ai.core.llm.domain.responses.ResponsesResponse;
import ai.core.llm.domain.responses.ResponsesUsage;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public class ResponsesResponseMapper {
    public ResponsesResponse base(ResponsesRequest request, String responseId, long createdAt, String status) {
        var response = new ResponsesResponse();
        response.id = responseId;
        response.createdAt = createdAt;
        response.status = status;
        response.instructions = request.instructions;
        response.maxOutputTokens = request.maxOutputTokens;
        response.model = request.model;
        response.output = List.of();
        response.parallelToolCalls = request.parallelToolCalls == null ? Boolean.TRUE : request.parallelToolCalls;
        response.previousResponseId = null;
        response.reasoning = request.reasoning == null ? new ResponsesReasoning() : request.reasoning;
        response.store = request.store == null ? Boolean.TRUE : request.store;
        response.temperature = request.temperature == null ? 1.0 : request.temperature;
        response.text = request.text == null ? Map.of("format", Map.of("type", "text")) : request.text;
        response.toolChoice = request.toolChoice == null ? "auto" : request.toolChoice;
        response.tools = request.tools == null ? List.of() : request.tools;
        response.topP = request.topP == null ? 1.0 : request.topP;
        response.truncation = request.truncation == null ? "disabled" : request.truncation;
        response.user = request.user;
        response.metadata = request.metadata == null ? Map.of() : request.metadata;
        return response;
    }

    public ResponsesResponse completed(CompletionResponse completion, ResponsesRequest request, String responseId, long createdAt,
                                       String messageId, Map<Integer, String> functionItemIds) {
        var response = base(request, responseId, createdAt, isIncomplete(completion) ? "incomplete" : "completed");
        response.completedAt = Instant.now().getEpochSecond();
        response.output = outputItems(completion, messageId, functionItemIds);
        response.usage = usage(completion == null ? null : completion.usage);
        if ("incomplete".equals(response.status)) {
            response.incompleteDetails = Map.of("reason", incompleteReason(completion));
        }
        return response;
    }

    public ResponsesResponse failed(ResponsesRequest request, String responseId, long createdAt, Throwable error) {
        var response = base(request, responseId, createdAt, "failed");
        response.output = List.of();
        response.error = new ResponsesError();
        response.error.code = "server_error";
        response.error.message = error.getMessage() == null ? "The model failed to generate a response." : error.getMessage();
        return response;
    }

    private List<ResponsesOutputItem> outputItems(CompletionResponse completion, String messageId, Map<Integer, String> functionItemIds) {
        if (completion == null || completion.choices == null || completion.choices.isEmpty()) return List.of();
        var message = completion.choices.getFirst().message;
        if (message == null) return List.of();
        var output = new ArrayList<ResponsesOutputItem>();
        if (message.content != null && !message.content.isEmpty()) {
            output.add(messageItem(messageId, "completed", message.content));
        }
        if (message.toolCalls != null) {
            for (int i = 0; i < message.toolCalls.size(); i++) {
                var toolCall = message.toolCalls.get(i);
                if (toolCall != null) {
                    var itemId = functionItemIds.get(i);
                    output.add(functionItem(itemId == null ? newFunctionItemId() : itemId, toolCall, "completed"));
                }
            }
        }
        return output;
    }

    ResponsesOutputItem messageItem(String id, String status, String text) {
        var item = new ResponsesOutputItem();
        item.id = id;
        item.type = "message";
        item.status = status;
        item.role = "assistant";
        var part = new ResponsesContentPart();
        part.type = "output_text";
        part.text = text == null ? "" : text;
        part.annotations = Collections.emptyList();
        item.content = List.of(part);
        return item;
    }

    ResponsesOutputItem messageItemInProgress(String id) {
        var item = new ResponsesOutputItem();
        item.id = id;
        item.type = "message";
        item.status = "in_progress";
        item.role = "assistant";
        item.content = List.of();
        return item;
    }

    ResponsesContentPart outputTextPart(String text) {
        var part = new ResponsesContentPart();
        part.type = "output_text";
        part.text = text == null ? "" : text;
        part.annotations = Collections.emptyList();
        return part;
    }

    ResponsesOutputItem functionItem(String id, FunctionCall toolCall, String status) {
        var item = new ResponsesOutputItem();
        item.id = id;
        item.type = "function_call";
        item.status = status;
        item.callId = toolCall.id;
        if (toolCall.function != null) {
            item.name = toolCall.function.name;
            item.arguments = toolCall.function.arguments == null ? "" : toolCall.function.arguments;
        } else {
            item.arguments = "";
        }
        return item;
    }

    private String newFunctionItemId() {
        return "fc_" + UUID.randomUUID().toString().replace("-", "");
    }

    private ResponsesUsage usage(Usage usage) {
        if (usage == null) return null;
        var mapped = new ResponsesUsage();
        mapped.inputTokens = usage.getPromptTokens();
        mapped.outputTokens = usage.getCompletionTokens();
        mapped.totalTokens = usage.getTotalTokens();
        if (usage.getPromptTokensDetails() != null) {
            mapped.inputTokensDetails = new ResponsesUsage.InputTokensDetails();
            mapped.inputTokensDetails.cachedTokens = usage.getPromptTokensDetails().cachedTokens;
        }
        if (usage.getCompletionTokensDetails() != null) {
            mapped.outputTokensDetails = new ResponsesUsage.OutputTokensDetails();
            mapped.outputTokensDetails.reasoningTokens = usage.getCompletionTokensDetails().reasoningTokens;
        }
        return mapped;
    }

    private boolean isIncomplete(CompletionResponse completion) {
        return completion != null && completion.choices != null && !completion.choices.isEmpty()
                && (completion.choices.getFirst().finishReason == FinishReason.LENGTH
                || completion.choices.getFirst().finishReason == FinishReason.CONTENT_FILTER);
    }

    private String incompleteReason(CompletionResponse completion) {
        var reason = completion.choices.getFirst().finishReason;
        return reason == FinishReason.CONTENT_FILTER ? "content_filter" : "max_output_tokens";
    }

    Map<String, Object> event(String type, long sequenceNumber) {
        var event = new LinkedHashMap<String, Object>();
        event.put("type", type);
        event.put("sequence_number", sequenceNumber);
        return event;
    }
}
