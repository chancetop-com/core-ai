package ai.core.llm.providers.inner;

import ai.core.agent.AgentRole;
import ai.core.document.Embedding;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
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
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.FunctionCall;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.CompletionsUsage;
import com.azure.ai.openai.models.CompletionsFinishReason;
import com.azure.ai.openai.models.ChatResponseMessage;
import com.azure.ai.openai.models.ChatRole;
import com.azure.core.util.BinaryData;
import core.framework.json.JSON;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

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
        if (request.toolCalls != null && !request.toolCalls.isEmpty()) {
            options.setTools(request.toolCalls.stream().map(AzureOpenAIModelsUtil::fromToolCall).toList());
        }
        return options;
    }

    public static List<Choice> toChoice(List<ChatChoice> choices, String name) {
        return choices.stream().map(v -> new Choice(toFinishReason(v.getFinishReason()), toMessage(v.getMessage(), name))).toList();
    }

    public static Usage toUsage(CompletionsUsage usage) {
        return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    public static EmbeddingResponse toEmbeddingResponse(EmbeddingRequest request, Embeddings embeddings) {
        return new EmbeddingResponse(embeddings.getData().stream().map(v -> new EmbeddingResponse.EmbeddingData(request.query().get(v.getPromptIndex()), Embedding.of(v.getEmbedding()))).toList());
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

    private static ai.core.llm.providers.inner.FunctionCall toFunctionCall(FunctionCall v) {
        if (v == null) return null;
        return ai.core.llm.providers.inner.FunctionCall.of(
                null,
                null,
                Function.of(v.getName(), v.getArguments()));
    }

    private static ai.core.llm.providers.inner.FunctionCall toFunctionCall(ChatCompletionsFunctionToolCall v) {
        return ai.core.llm.providers.inner.FunctionCall.of(
                v.getId(),
                v.getType(),
                Function.of(v.getFunction().getName(), v.getFunction().getArguments()));
    }

    private static AgentRole toAgentRole(ChatRole role) {
        return AgentRole.valueOf(role.getValue().toUpperCase(Locale.ROOT));
    }

    private static List<ChatRequestMessage> fromMessages(List<Message> messages) {
        return messages.stream().map(msg -> {
            if (msg.role == AgentRole.SYSTEM) {
                return new ChatRequestSystemMessage(msg.content);
            } else if (msg.role == AgentRole.ASSISTANT) {
                var message = new ChatRequestAssistantMessage(msg.content);
                message.setName(msg.name);
                message.setToolCalls(msg.toolCalls == null || msg.toolCalls.isEmpty() ? null : msg.toolCalls.stream().map(AzureOpenAIModelsUtil::fromToolCall).toList());
                message.setFunctionCall(fromFunctionCall(msg.functionCall));
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
        var func = new ChatCompletionsFunctionToolDefinitionFunction(toolCall.getName());
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
//            property.type = ParameterTypeView.valueOf(p.getType().getTypeName().substring(p.getType().getTypeName().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT));
            property.type = ParameterTypeView.STRING;
            return property;
        }));
        return ajax;
    }
}
