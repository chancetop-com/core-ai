package ai.core.llm.providers;

import ai.core.agent.AgentRole;
import ai.core.litellm.LiteLLMService;
import ai.core.litellm.completion.CreateCompletionAJAXRequest;
import ai.core.litellm.completion.CreateCompletionAJAXResponse;
import ai.core.litellm.completion.CreateImageCompletionAJAXRequest;
import ai.core.litellm.completion.FunctionAJAXView;
import ai.core.litellm.completion.FunctionCallAJAXView;
import ai.core.litellm.completion.ImageMessageAJAXView;
import ai.core.litellm.completion.MessageAJAXView;
import ai.core.litellm.completion.ParameterAJAXView;
import ai.core.litellm.completion.ParameterTypeView;
import ai.core.litellm.completion.PropertyAJAXView;
import ai.core.litellm.completion.RoleTypeAJAXView;
import ai.core.litellm.completion.ToolAJAXView;
import ai.core.litellm.completion.ToolTypeAJAXView;
import ai.core.litellm.embedding.CreateEmbeddingAJAXRequest;
import ai.core.llm.LLMProviderConfig;
import ai.core.llm.providers.inner.CaptionImageRequest;
import ai.core.llm.providers.inner.CaptionImageResponse;
import ai.core.llm.providers.inner.EmbeddingRequest;
import ai.core.llm.providers.inner.EmbeddingResponse;
import ai.core.llm.providers.inner.Choice;
import ai.core.llm.providers.inner.CompletionRequest;
import ai.core.llm.providers.inner.CompletionResponse;
import ai.core.llm.providers.inner.FinishReason;
import ai.core.llm.providers.inner.Function;
import ai.core.llm.providers.inner.FunctionCall;
import ai.core.llm.LLMProvider;
import ai.core.llm.providers.inner.Message;
import ai.core.llm.providers.inner.Usage;
import ai.core.rag.Embedding;
import ai.core.tool.ToolCallParameter;
import core.framework.inject.Inject;
import core.framework.util.Strings;

import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class LiteLLMProvider extends LLMProvider {
    @Inject
    LiteLLMService liteLLMService;

    public LiteLLMProvider(LLMProviderConfig config) {
        super(config);
    }

    @Override
    public CompletionResponse completion(CompletionRequest dto) {
        return toRsp(this.liteLLMService.completion(toApiRequest(dto)), dto.messages.getLast().name);
    }

    @Override
    public EmbeddingResponse embedding(EmbeddingRequest dto) {
        return new EmbeddingResponse(new Embedding(this.liteLLMService.embedding(toApiRequest(dto)).data.getFirst().embedding));
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest dto) {
        return new CaptionImageResponse(liteLLMService.imageCompletion(toApiRequest(dto)).choices.getFirst().message.content);
    }

    @Override
    public int maxTokens() {
        // 30k context * 10 round
        return 30 * 1000 * 10;
    }

    private CompletionResponse toRsp(CreateCompletionAJAXResponse rsp, String name) {
        return new CompletionResponse(rsp.choices.stream().map(v ->
                new Choice(FinishReason.valueOf(v.finishReason.name()), Message.of(
                        AgentRole.valueOf(v.message.role.name()),
                        v.message.content,
                        name,
                        v.message.toolCallId,
                        v.message.functionCall == null ? null : buildFunctionCall(v.message.functionCall),
                        v.message.toolCalls == null ? null : v.message.toolCalls.stream().map(this::buildFunctionCall).collect(Collectors.toList())))).toList(),
                new Usage(rsp.usage));
    }

    private FunctionCall buildFunctionCall(FunctionCallAJAXView v) {
        return FunctionCall.of(v.id,
                v.type, Function.of(v.function.name,
                v.function.arguments));
    }

    private CreateEmbeddingAJAXRequest toApiRequest(EmbeddingRequest dto) {
        var req = new CreateEmbeddingAJAXRequest();
        req.input = dto.query();
        return req;
    }

    private CreateImageCompletionAJAXRequest toApiRequest(CaptionImageRequest dto) {
        var apiReq = new CreateImageCompletionAJAXRequest();
        apiReq.model = dto.model() != null ? dto.model() : config.getModel();
        var message = new ImageMessageAJAXView();
        message.role = RoleTypeAJAXView.USER;
        var textContent = new ImageMessageAJAXView.ImageContent();
        textContent.type = ImageMessageAJAXView.Type.TEXT;
        textContent.text = dto.query();
        var urlContent = new ImageMessageAJAXView.ImageContent();
        urlContent.type = ImageMessageAJAXView.Type.IMAGE_URL;
        urlContent.imageUrl = new ImageMessageAJAXView.ImageUrl();
        urlContent.imageUrl.url = dto.url();
        message.content = List.of(textContent, urlContent);
        apiReq.messages = List.of(message);
        return apiReq;
    }

    private CreateCompletionAJAXRequest toApiRequest(CompletionRequest dto) {
        var apiReq = new CreateCompletionAJAXRequest();
        apiReq.model = Strings.isBlank(dto.model) ? config.getModel() : dto.model;
        apiReq.temperature = dto.temperature != null ? dto.temperature : config.getTemperature();
        apiReq.messages = dto.messages.stream().map(v -> {
            var message = new MessageAJAXView();
            message.role = RoleTypeAJAXView.valueOf(v.role.name());
            message.content = v.content;
            message.name = v.name;
            message.toolCallId = v.toolCallId;
            if (v.functionCall != null) {
                var functionCall = new FunctionCallAJAXView();
                functionCall.id = v.functionCall.id;
                functionCall.type = v.functionCall.type;
                functionCall.function = new FunctionCallAJAXView.Function();
                functionCall.function.name = v.functionCall.function.name;
                functionCall.function.arguments = v.functionCall.function.arguments;
                message.functionCall = functionCall;
            }
            return message;
        }).collect(Collectors.toList());
        apiReq.tools = dto.toolCalls.isEmpty() ? null : dto.toolCalls.stream().map(v -> {
            var tool = new ToolAJAXView();
            tool.type = ToolTypeAJAXView.FUNCTION;
            var function = new FunctionAJAXView();
            function.name = v.getName();
            function.description = v.getDescription();
            var parameters = new ParameterAJAXView();
            parameters.type = ParameterTypeView.OBJECT;
            parameters.required = v.getParameters().stream().filter(ToolCallParameter::getRequired).map(ToolCallParameter::getName).toList();
            parameters.properties = v.getParameters().stream().collect(Collectors.toMap(ToolCallParameter::getName, p -> {
                var property = new PropertyAJAXView();
                property.description = p.getDescription();
                property.type = ParameterTypeView.valueOf(p.getType().getTypeName().substring(p.getType().getTypeName().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT));
                return property;
            }));
            function.parameters = parameters;
            tool.function = function;
            return tool;
        }).collect(Collectors.toList());
        apiReq.toolChoice = dto.toolCalls.isEmpty() ? null : "auto";
        return apiReq;
    }
}
