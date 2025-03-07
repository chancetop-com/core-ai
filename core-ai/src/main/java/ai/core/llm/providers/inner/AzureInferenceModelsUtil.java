package ai.core.llm.providers.inner;

import ai.core.agent.AgentRole;
import ai.core.rag.Embedding;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
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
import com.azure.ai.inference.models.EmbeddingsResult;
import com.azure.ai.inference.models.FunctionCall;
import com.azure.ai.inference.models.ChatRequestUserMessage;
import com.azure.ai.inference.models.FunctionDefinition;
import com.azure.ai.inference.models.ChatChoice;
import com.azure.ai.inference.models.CompletionsUsage;
import com.azure.ai.inference.models.CompletionsFinishReason;
import com.azure.ai.inference.models.ChatResponseMessage;
import com.azure.ai.inference.models.ChatRole;
import com.azure.core.util.BinaryData;
import core.framework.json.JSON;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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
        if (request.toolCalls != null && !request.toolCalls.isEmpty()) {
            options.setTools(request.toolCalls.stream().map(AzureInferenceModelsUtil::fromToolCall).toList());
        }
        return options;
    }

    public static List<Choice> toChoice(List<ChatChoice> choices) {
        return choices.stream().map(v -> new Choice(toFinishReason(v.getFinishReason()), toMessage(v.getMessage()))).toList();
    }

    public static Usage toUsage(CompletionsUsage usage) {
        return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    public static EmbeddingResponse toEmbeddingResponse(EmbeddingsResult embeddings) {
        var data = embeddings.getData().getFirst().getEmbeddingList().stream().map(Float::doubleValue).toList();
        return new EmbeddingResponse(new Embedding(data));
    }

    private static FinishReason toFinishReason(CompletionsFinishReason finishReason) {
        return FinishReason.valueOf(finishReason.toString().toUpperCase(Locale.ROOT));
    }

    private static Message toMessage(ChatResponseMessage message) {
        return Message.of(
                toAgentRole(message.getRole()),
                message.getContent(),
                null,
                null,
                null,
                message.getToolCalls() == null || message.getToolCalls().isEmpty() ? null : message.getToolCalls().stream().map(AzureInferenceModelsUtil::toFunctionCall).toList());
    }

    private static ai.core.llm.providers.inner.FunctionCall toFunctionCall(ChatCompletionsToolCall v) {
        return ai.core.llm.providers.inner.FunctionCall.of(
                v.getId(),
                v.getType(),
                Function.of(v.getFunction().getName(), v.getFunction().getArguments()));
    }

    private static AgentRole toAgentRole(ChatRole role) {
        return AgentRole.valueOf(role.getValue().toUpperCase(Locale.ROOT));
    }

    private static List<ChatRequestMessage> fromMessages(String model, List<Message> messages) {
        return messages.stream().map(msg -> {
            if (msg.role == AgentRole.SYSTEM && !model.startsWith("o1")) {
                return new ChatRequestSystemMessage(msg.content);
            } else if (msg.role == AgentRole.ASSISTANT) {
                var message = new ChatRequestAssistantMessage(msg.content);
                message.setToolCalls(msg.toolCalls == null || msg.toolCalls.isEmpty() ? null : msg.toolCalls.stream().map(AzureInferenceModelsUtil::fromToolCall).toList());
                return message;
            } else {
                return new ChatRequestUserMessage(msg.content);
            }
        }).toList();
    }

    private static ChatCompletionsToolCall fromToolCall(ai.core.llm.providers.inner.FunctionCall toolCall) {
        return new ChatCompletionsFunctionToolCall(toolCall.id, fromFunctionCall(toolCall));
    }

    private static ChatCompletionsToolDefinition fromToolCall(ToolCall toolCall) {
        var func = new FunctionDefinition(toolCall.getName());
        func.setDescription(toolCall.getDescription());
        func.setParameters(fromParameter(toolCall.getParameters()));
        return new ChatCompletionsFunctionToolDefinition(func);
    }

    private static FunctionCall fromFunctionCall(ai.core.llm.providers.inner.FunctionCall functionCall) {
        if (functionCall == null) return null;
        return new FunctionCall(functionCall.function.name, functionCall.function.arguments);
    }

    private static BinaryData fromParameter(List<ToolCallParameter> parameters) {
        return BinaryData.fromString(JSON.toJSON(toParameter(parameters)));
    }

    private static ParameterObjectView toParameter(List<ToolCallParameter> parameters) {
        var ajax = new ParameterObjectView();
        ajax.type = ParameterTypeView.OBJECT;
        ajax.required = parameters.stream().filter(ToolCallParameter::getRequired).map(ToolCallParameter::getName).toList();
        ajax.properties = parameters.stream().collect(Collectors.toMap(ToolCallParameter::getName, p -> {
            var property = new PropertyView();
            property.description = p.getDescription();
            property.type = ParameterTypeView.valueOf(p.getType().getTypeName().substring(p.getType().getTypeName().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT));
            return property;
        }));
        return ajax;
    }
}
