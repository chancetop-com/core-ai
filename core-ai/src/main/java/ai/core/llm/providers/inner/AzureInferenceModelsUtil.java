package ai.core.llm.providers.inner;

import ai.core.api.jsonschema.JsonSchema;
import ai.core.document.Embedding;
import ai.core.llm.domain.CaptionImageRequest;
import ai.core.llm.domain.Choice;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.EmbeddingRequest;
import ai.core.llm.domain.EmbeddingResponse;
import ai.core.llm.domain.FinishReason;
import ai.core.llm.domain.FunctionCall;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.llm.domain.Tool;
import ai.core.llm.domain.Usage;
import ai.core.utils.JsonUtil;
import com.azure.ai.inference.models.ChatCompletionsFunctionToolCall;
import com.azure.ai.inference.models.ChatCompletionsFunctionToolDefinition;
import com.azure.ai.inference.models.ChatCompletionsOptions;
import com.azure.ai.inference.models.ChatCompletionsToolCall;
import com.azure.ai.inference.models.ChatCompletionsToolDefinition;
import com.azure.ai.inference.models.ChatMessageImageContentItem;
import com.azure.ai.inference.models.ChatMessageImageUrl;
import com.azure.ai.inference.models.ChatMessageTextContentItem;
import com.azure.ai.inference.models.ChatRequestAssistantMessage;
import com.azure.ai.inference.models.ChatRequestMessage;
import com.azure.ai.inference.models.ChatRequestSystemMessage;
import com.azure.ai.inference.models.ChatRequestToolMessage;
import com.azure.ai.inference.models.EmbeddingsResult;
import com.azure.ai.inference.models.EmbeddingsUsage;
import com.azure.ai.inference.models.ChatRequestUserMessage;
import com.azure.ai.inference.models.FunctionDefinition;
import com.azure.ai.inference.models.ChatChoice;
import com.azure.ai.inference.models.CompletionsUsage;
import com.azure.ai.inference.models.CompletionsFinishReason;
import com.azure.ai.inference.models.ChatResponseMessage;
import com.azure.ai.inference.models.ChatRole;
import com.azure.ai.inference.models.StreamingChatChoiceUpdate;
import com.azure.ai.inference.models.StreamingChatResponseMessageUpdate;
import com.azure.ai.inference.models.StreamingChatResponseToolCallUpdate;
import com.azure.core.util.BinaryData;

import java.util.List;
import java.util.Locale;

/**
 * @author stephen
 */
public class AzureInferenceModelsUtil {
    public static ChatCompletionsOptions toAzureRequest(CaptionImageRequest request) {
        var contentText = new ChatMessageTextContentItem(request.query());
        var contentUrl = new ChatMessageImageContentItem(new ChatMessageImageUrl(request.url()));
        var systemMessage = new ChatRequestSystemMessage("You are a helpful assistant.");
        var options = new ChatCompletionsOptions(List.of(systemMessage, ChatRequestUserMessage.fromContentItems(List.of(contentText, contentUrl))));
        options.setModel(request.model());
        return options;
    }

    public static ChatCompletionsOptions toAzureRequest(CompletionRequest request) {
        var options = new ChatCompletionsOptions(fromMessages(request.model, request.messages));
        options.setModel(request.model);
        options.setTemperature(request.temperature);
        if (request.tools != null && !request.tools.isEmpty()) {
            options.setTools(request.tools.stream().map(AzureInferenceModelsUtil::fromToolCall).toList());
        }
        return options;
    }

    public static List<Choice> toChoice(List<ChatChoice> choices, String name) {
        return choices.stream().map(v -> Choice.of(toFinishReason(v.getFinishReason()), toMessage(v.getMessage(), name))).toList();
    }

    public static List<Choice> toChoiceStream(List<StreamingChatChoiceUpdate> choices, String name) {
        return choices.stream().map(v -> Choice.of(toFinishReason(v.getFinishReason()), toMessage(v.getDelta(), name))).toList();
    }

    public static Usage toUsage(CompletionsUsage usage) {
        return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    public static Usage toUsage(EmbeddingsUsage usage) {
        return new Usage(usage.getPromptTokens(), 0, usage.getTotalTokens());
    }

    public static EmbeddingResponse toEmbeddingResponse(EmbeddingRequest request, EmbeddingsResult embeddings) {
        return EmbeddingResponse.of(
                embeddings.getData()
                        .stream()
                        .map(v -> EmbeddingResponse.EmbeddingData.of(request.query().get(v.getIndex()), Embedding.of(v.getEmbeddingList())))
                        .toList(),
                toUsage(embeddings.getUsage()));
    }

    private static FinishReason toFinishReason(CompletionsFinishReason finishReason) {
        return FinishReason.valueOf(finishReason.toString().toUpperCase(Locale.ROOT));
    }

    private static Message toMessage(ChatResponseMessage message, String name) {
        return Message.of(
                toAgentRole(message.getRole()),
                message.getContent(),
                name,
                null,
                null,
                message.getToolCalls() == null || message.getToolCalls().isEmpty() ? null : message.getToolCalls().stream().map(AzureInferenceModelsUtil::toFunctionCall).toList());
    }

    private static Message toMessage(StreamingChatResponseMessageUpdate message, String name) {
        return Message.of(
                toAgentRole(message.getRole()),
                message.getContent(),
                name,
                null,
                null,
                message.getToolCalls() == null || message.getToolCalls().isEmpty() ? null : message.getToolCalls().stream().map(AzureInferenceModelsUtil::toFunctionCall).toList());
    }

    public static FunctionCall toFunctionCall(StreamingChatResponseToolCallUpdate v) {
        return FunctionCall.of(
                v.getId(),
                "function",
                v.getFunction().getName(),
                v.getFunction().getArguments());
    }

    private static FunctionCall toFunctionCall(ChatCompletionsToolCall v) {
        return FunctionCall.of(
                v.getId(),
                v.getType(),
                v.getFunction().getName(),
                v.getFunction().getArguments());
    }

    public static RoleType toAgentRole(ChatRole role) {
        return RoleType.valueOf(role.getValue().toUpperCase(Locale.ROOT));
    }

    private static List<ChatRequestMessage> fromMessages(String model, List<Message> messages) {
        return messages.stream().map(msg -> {
            if (msg.role == RoleType.SYSTEM && !model.startsWith("o1")) {
                return new ChatRequestSystemMessage(msg.content);
            } else if (msg.role == RoleType.ASSISTANT) {
                var message = new ChatRequestAssistantMessage(msg.content);
                message.setToolCalls(msg.toolCalls == null || msg.toolCalls.isEmpty() ? null : msg.toolCalls.stream().map(AzureInferenceModelsUtil::fromToolCall).toList());
                return message;
            } else if (msg.role == RoleType.TOOL) {
                var message = new ChatRequestToolMessage(msg.toolCallId);
                message.setContent(msg.content);
                return message;
            } else {
                return new ChatRequestUserMessage(msg.content);
            }
        }).toList();
    }

    private static ChatCompletionsToolCall fromToolCall(FunctionCall toolCall) {
        var func = toolCall.function == null ? null : new com.azure.ai.inference.models.FunctionCall(toolCall.function.name, toolCall.function.arguments);
        return new ChatCompletionsFunctionToolCall(toolCall.id, func);
    }

    private static ChatCompletionsToolDefinition fromToolCall(Tool toolCall) {
        var func = new FunctionDefinition(toolCall.function.name);
        func.setDescription(toolCall.function.description);
        func.setParameters(fromParameter(toolCall.function.parameters));
        return new ChatCompletionsFunctionToolDefinition(func);
    }

    private static BinaryData fromParameter(JsonSchema parameters) {
        return BinaryData.fromString(JsonUtil.toJson(parameters));
    }
}
