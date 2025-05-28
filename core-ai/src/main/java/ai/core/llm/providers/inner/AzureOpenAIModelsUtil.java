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
import com.azure.ai.openai.models.ChatMessageImageContentItem;
import com.azure.ai.openai.models.ChatMessageImageUrl;
import com.azure.ai.openai.models.ChatMessageTextContentItem;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolCall;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinition;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinitionFunction;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatCompletionsToolCall;
import com.azure.ai.openai.models.ChatCompletionsToolDefinition;
import com.azure.ai.openai.models.ChatRequestAssistantMessage;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestToolMessage;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsUsage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.CompletionsUsage;
import com.azure.ai.openai.models.CompletionsFinishReason;
import com.azure.ai.openai.models.ChatResponseMessage;
import com.azure.ai.openai.models.ChatRole;
import com.azure.core.util.BinaryData;

import java.util.List;
import java.util.Locale;

/**
 * @author stephen
 */
public class AzureOpenAIModelsUtil {
    public static ChatCompletionsOptions toAzureRequest(CaptionImageRequest request) {
        var contentText = new ChatMessageTextContentItem(request.query());
        var contentUrl = new ChatMessageImageContentItem(new ChatMessageImageUrl(request.url()));
        var systemMessage = new ChatRequestSystemMessage("You are a helpful assistant.");
        var options = new ChatCompletionsOptions(List.of(systemMessage, new ChatRequestUserMessage(List.of(contentText, contentUrl))));
        options.setModel(request.model());
        return options;
    }

    public static ChatCompletionsOptions toAzureRequest(CompletionRequest request) {
        var options = new ChatCompletionsOptions(fromMessages(request.messages));
        options.setModel(request.model);
        options.setTemperature(request.temperature);
        if (request.tools != null && !request.tools.isEmpty()) {
            options.setTools(request.tools.stream().map(AzureOpenAIModelsUtil::fromToolCall).toList());
        }
        return options;
    }

    public static List<Choice> toChoice(List<ChatChoice> choices, String name) {
        return choices.stream().map(v -> Choice.of(toFinishReason(v.getFinishReason()), toMessage(v.getMessage(), name))).toList();
    }

    public static Usage toUsage(CompletionsUsage usage) {
        return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    public static Usage toUsage(EmbeddingsUsage usage) {
        return new Usage(usage.getPromptTokens(), 0, usage.getTotalTokens());
    }

    public static EmbeddingResponse toEmbeddingResponse(EmbeddingRequest request, Embeddings embeddings) {
        return EmbeddingResponse.of(
                embeddings.getData()
                        .stream()
                        .map(v -> EmbeddingResponse.EmbeddingData.of(request.query().get(v.getPromptIndex()), Embedding.of(v.getEmbedding())))
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
                toFunctionCall(message.getFunctionCall()),
                message.getToolCalls() == null || message.getToolCalls().isEmpty() ? null : message.getToolCalls().stream().map(v -> toFunctionCall((ChatCompletionsFunctionToolCall) v)).toList());
    }

    private static FunctionCall toFunctionCall(com.azure.ai.openai.models.FunctionCall v) {
        if (v == null) return null;
        return FunctionCall.of(
                null,
                null,
                v.getName(), v.getArguments());
    }

    private static FunctionCall toFunctionCall(ChatCompletionsFunctionToolCall v) {
        return FunctionCall.of(
                v.getId(),
                v.getType(),
                v.getFunction().getName(), v.getFunction().getArguments());
    }

    private static RoleType toAgentRole(ChatRole role) {
        return RoleType.valueOf(role.getValue().toUpperCase(Locale.ROOT));
    }

    private static List<ChatRequestMessage> fromMessages(List<Message> messages) {
        return messages.stream().map(msg -> {
            if (msg.role == RoleType.SYSTEM) {
                return new ChatRequestSystemMessage(msg.content);
            } else if (msg.role == RoleType.ASSISTANT) {
                var message = new ChatRequestAssistantMessage(msg.content);
                message.setToolCalls(msg.toolCalls == null || msg.toolCalls.isEmpty() ? null : msg.toolCalls.stream().map(AzureOpenAIModelsUtil::fromToolCall).toList());
                message.setFunctionCall(fromFunctionCall(msg.functionCall));
                return message;
            } else if (msg.role == RoleType.TOOL) {
                return new ChatRequestToolMessage(msg.content, msg.toolCallId);
            } else {
                return new ChatRequestUserMessage(msg.content);
            }
        }).toList();
    }

    private static ChatCompletionsToolCall fromToolCall(FunctionCall toolCall) {
        return new ChatCompletionsFunctionToolCall(toolCall.id, fromFunctionCall(toolCall));
    }

    private static ChatCompletionsToolDefinition fromToolCall(Tool toolCall) {
        var func = new ChatCompletionsFunctionToolDefinitionFunction(toolCall.function.name);
        func.setDescription(toolCall.function.description);
        func.setParameters(fromParameter(toolCall.function.parameters));
        return new ChatCompletionsFunctionToolDefinition(func);
    }

    private static com.azure.ai.openai.models.FunctionCall fromFunctionCall(FunctionCall functionCall) {
        if (functionCall == null) return null;
        return new com.azure.ai.openai.models.FunctionCall(functionCall.function.name, functionCall.function.arguments);
    }

    private static BinaryData fromParameter(JsonSchema parameters) {
        return BinaryData.fromString(JsonUtil.toJson(parameters));
    }
}
