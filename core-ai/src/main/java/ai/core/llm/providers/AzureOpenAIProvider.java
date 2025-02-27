package ai.core.llm.providers;

import ai.core.agent.AgentRole;
import ai.core.llm.LLMProvider;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.inner.CaptionImageRequest;
import ai.core.llm.providers.inner.CaptionImageResponse;
import ai.core.llm.providers.inner.Choice;
import ai.core.llm.providers.inner.CompletionRequest;
import ai.core.llm.providers.inner.CompletionResponse;
import ai.core.llm.providers.inner.EmbeddingRequest;
import ai.core.llm.providers.inner.EmbeddingResponse;
import ai.core.llm.providers.inner.FinishReason;
import ai.core.llm.providers.inner.Function;
import ai.core.llm.providers.inner.Message;
import ai.core.llm.providers.inner.Usage;
import ai.core.rag.Embedding;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import com.azure.ai.openai.OpenAIClient;
import com.azure.ai.openai.OpenAIClientBuilder;
import com.azure.ai.openai.models.ChatChoice;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolCall;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinition;
import com.azure.ai.openai.models.ChatCompletionsFunctionToolDefinitionFunction;
import com.azure.ai.openai.models.ChatCompletionsOptions;
import com.azure.ai.openai.models.ChatCompletionsToolCall;
import com.azure.ai.openai.models.ChatCompletionsToolDefinition;
import com.azure.ai.openai.models.ChatRequestAssistantMessage;
import com.azure.ai.openai.models.ChatRequestMessage;
import com.azure.ai.openai.models.ChatRequestSystemMessage;
import com.azure.ai.openai.models.ChatRequestUserMessage;
import com.azure.ai.openai.models.ChatResponseMessage;
import com.azure.ai.openai.models.ChatRole;
import com.azure.ai.openai.models.CompletionsFinishReason;
import com.azure.ai.openai.models.CompletionsUsage;
import com.azure.ai.openai.models.Embeddings;
import com.azure.ai.openai.models.EmbeddingsOptions;
import com.azure.ai.openai.models.FunctionCall;
import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.credential.KeyCredential;
import com.azure.core.util.BinaryData;
import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;
import core.framework.json.JSON;
import core.framework.util.Strings;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class AzureOpenAIProvider extends LLMProvider {
    private final OpenAIClient client;

    public AzureOpenAIProvider(LLMProviderConfig config, String apiKey, String endpoint) {
        super(config);
        if (endpoint == null) {
            // openai api key without endpoint
            this.client = new OpenAIClientBuilder().credential(new KeyCredential(apiKey)).buildClient();
        } else {
            this.client = new OpenAIClientBuilder().credential(new AzureKeyCredential(apiKey)).endpoint(endpoint).buildClient();
        }
    }

    @Override
    public CompletionResponse completion(CompletionRequest request) {
        var chatCompletion = client.getChatCompletions(getModel(request), toAzureRequest(request));
        return new CompletionResponse(toChoice(chatCompletion.getChoices()), toUsage(chatCompletion.getUsage()));
    }

    @Override
    public EmbeddingResponse embedding(EmbeddingRequest request) {
        var embeddingsOptions = new EmbeddingsOptions(List.of(request.query()));
        var embeddings = client.getEmbeddings(config.getEmbeddingModel(), embeddingsOptions);
        return toEmbeddingResponse(embeddings);
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest request) {
        return null;
    }

    private ChatCompletionsOptions toAzureRequest(CompletionRequest request) {
        var options = new ChatCompletionsOptions(fromMessages(request.messages));
        if (request.toolCalls != null && !request.toolCalls.isEmpty()) {
            options.setTools(request.toolCalls.stream().map(this::fromToolCall).toList());
        }
        return options;
    }

    private List<ChatRequestMessage> fromMessages(List<Message> messages) {
        return messages.stream().map(msg -> {
            if (msg.role == AgentRole.SYSTEM) {
                return new ChatRequestSystemMessage(msg.content);
            } else if (msg.role == AgentRole.ASSISTANT) {
                var message = new ChatRequestAssistantMessage(msg.content);
                message.setName(msg.name);
                message.setToolCalls(msg.toolCalls == null || msg.toolCalls.isEmpty() ? null : msg.toolCalls.stream().map(this::fromToolCall).toList());
                message.setFunctionCall(fromFunctionCall(msg.functionCall));
                return message;
            } else {
                return new ChatRequestUserMessage(msg.content);
            }
        }).toList();
    }

    private ChatCompletionsToolCall fromToolCall(ai.core.llm.providers.inner.FunctionCall toolCall) {
        return new ChatCompletionsFunctionToolCall(toolCall.id, fromFunctionCall(toolCall));
    }

    private ChatCompletionsToolDefinition fromToolCall(ToolCall toolCall) {
        var func = new ChatCompletionsFunctionToolDefinitionFunction(toolCall.getName());
        func.setDescription(toolCall.getDescription());
        func.setParameters(fromParameter(toolCall.getParameters()));
        return new ChatCompletionsFunctionToolDefinition(func);
    }

    private FunctionCall fromFunctionCall(ai.core.llm.providers.inner.FunctionCall functionCall) {
        if (functionCall == null) return null;
        return new FunctionCall(functionCall.function.name, functionCall.function.arguments);
    }

    private BinaryData fromParameter(List<ToolCallParameter> parameters) {
        return BinaryData.fromString(JSON.toJSON(toParameter(parameters)));
    }

    private Usage toUsage(CompletionsUsage usage) {
        return new Usage(usage.getPromptTokens(), usage.getCompletionTokens(), usage.getTotalTokens());
    }

    private List<Choice> toChoice(List<ChatChoice> choices) {
        return choices.stream().map(v -> new Choice(toFinishReason(v.getFinishReason()), toMessage(v.getMessage()))).toList();
    }

    private Message toMessage(ChatResponseMessage message) {
        return Message.of(
                toAgentRole(message.getRole()),
                message.getContent(),
                null,
                null,
                toFunctionCall(message.getFunctionCall()),
                message.getToolCalls() == null || message.getToolCalls().isEmpty() ? null : message.getToolCalls().stream().map(v -> toFunctionCall((ChatCompletionsFunctionToolCall) v)).toList());
    }

    private ai.core.llm.providers.inner.FunctionCall toFunctionCall(FunctionCall v) {
        if (v == null) return null;
        return ai.core.llm.providers.inner.FunctionCall.of(
                null,
                null,
                Function.of(v.getName(), v.getArguments()));
    }

    private ai.core.llm.providers.inner.FunctionCall toFunctionCall(ChatCompletionsFunctionToolCall v) {
        return ai.core.llm.providers.inner.FunctionCall.of(
                v.getId(),
                v.getType(),
                Function.of(v.getFunction().getName(), v.getFunction().getArguments()));
    }

    private AgentRole toAgentRole(ChatRole role) {
        return AgentRole.valueOf(role.getValue().toUpperCase(Locale.ROOT));
    }

    private FinishReason toFinishReason(CompletionsFinishReason finishReason) {
        return FinishReason.valueOf(finishReason.toString().toUpperCase(Locale.ROOT));
    }

    private String getModel(CompletionRequest request) {
        return Strings.isBlank(request.model) ? this.config.getModel() : request.model;
    }

    @Override
    public int maxTokens() {
        return 10000;
    }

    private ParameterObjectView toParameter(List<ToolCallParameter> parameters) {
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

    private EmbeddingResponse toEmbeddingResponse(Embeddings embeddings) {
        var data = embeddings.getData().getFirst().getEmbedding().stream().map(Float::doubleValue).toList();
        return new EmbeddingResponse(new Embedding(data));
    }

    public enum ParameterTypeView {
        @Property(name = "string")
        STRING,
        @Property(name = "object")
        OBJECT
    }

    public static class ParameterObjectView {
        @NotNull
        @Property(name = "type")
        public ParameterTypeView type;

        @NotNull
        @Property(name = "properties")
        public Map<String, PropertyView> properties;

        @NotNull
        @Property(name = "required")
        public List<String> required;
    }

    public static class PropertyView {
        @NotNull
        @Property(name = "type")
        public ParameterTypeView type;

        @NotNull
        @Property(name = "description")
        public String description;
    }
}
