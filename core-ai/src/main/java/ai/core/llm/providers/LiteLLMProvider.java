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
import ai.core.llm.providers.inner.LLMFunction;
import ai.core.llm.LLMProvider;
import ai.core.llm.providers.inner.LLMMessage;
import ai.core.llm.providers.inner.Usage;
import ai.core.document.Embedding;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.utils.JsonSchemaHelper;
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
        return toRsp(this.liteLLMService.completion(toApiRequest(dto)), dto.name);
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest dto) {
        var rsp = this.liteLLMService.embedding(toApiRequest(dto));
        return new EmbeddingResponse(rsp.data.stream().map(v -> new EmbeddingResponse.EmbeddingData(dto.query().get(v.index), new Embedding(v.embedding))).toList(), new Usage(rsp.usage));
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest dto) {
        var rsp = liteLLMService.imageCompletion(toApiRequest(dto));
        return new CaptionImageResponse(rsp.choices.getFirst().message.content, new Usage(rsp.usage));
    }

    @Override
    public int maxTokens() {
        return 128 * 1000;
    }

    @Override
    public String name() {
        return "litellm";
    }

    private CompletionResponse toRsp(CreateCompletionAJAXResponse rsp, String name) {
        return new CompletionResponse(rsp.choices.stream().map(v ->
                new Choice(FinishReason.valueOf(v.finishReason.name()), LLMMessage.of(
                        AgentRole.valueOf(v.message.role.name()),
                        v.message.content,
                        name,
                        v.message.toolCallId,
                        v.message.functionCall == null ? null : buildFunctionCall(v.message.functionCall),
                        v.message.toolCalls == null ? null : v.message.toolCalls.stream().map(this::buildFunctionCall).collect(Collectors.toList())))).toList(),
                new Usage(rsp.usage));
    }

    private LLMFunction.FunctionCall buildFunctionCall(FunctionCallAJAXView v) {
        return LLMFunction.FunctionCall.of(v.id,
                v.type, LLMFunction.of(v.function.name,
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
            message.content = v.content == null ? "" : v.content;
            message.toolCallId = v.toolCallId;
            if (v.functionCall != null) {
                message.functionCall = toFunction(v.functionCall);
            }
            if (v.toolCalls != null && !v.toolCalls.isEmpty()) {
                message.toolCalls = v.toolCalls.stream().map(this::toFunction).toList();
            }
            return message;
        }).collect(Collectors.toList());
        apiReq.tools = dto.toolCalls.isEmpty() ? null : dto.toolCalls.stream().map(v -> {
            var tool = new ToolAJAXView();
            tool.type = ToolTypeAJAXView.FUNCTION;
            var function = new FunctionAJAXView();
            function.name = v.getName();
            function.description = v.getDescription();
            function.parameters = toParameter(v.getParameters());
            tool.function = function;
            return tool;
        }).collect(Collectors.toList());
        apiReq.toolChoice = dto.toolCalls.isEmpty() ? null : "auto";
        return apiReq;
    }

    private FunctionCallAJAXView toFunction(LLMFunction.FunctionCall toolCall) {
        var function = new FunctionCallAJAXView();
        function.id = toolCall.id;
        function.type = toolCall.type;
        function.function = new FunctionCallAJAXView.Function();
        function.function.name = toolCall.function.name;
        function.function.arguments = toolCall.function.arguments;
        return function;
    }

    private ParameterAJAXView toParameter(List<ToolCallParameter> parameters) {
        var ajax = new ParameterAJAXView();
        ajax.type = ParameterTypeView.OBJECT;
        ajax.required = parameters.stream().filter(ToolCallParameter::getRequired).map(ToolCallParameter::getName).toList();
        ajax.properties = parameters.stream().collect(Collectors.toMap(ToolCallParameter::getName, p -> {
            var property = new PropertyAJAXView();
            property.description = p.getDescription();
            // todo: add support for format
//            property.format = p.getFormat();
//            property.type = ParameterTypeView.valueOf(p.getType().getTypeName().substring(p.getType().getTypeName().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT));
            property.type = JsonSchemaHelper.buildJsonSchemaType(p.getType()).name().toLowerCase(Locale.ROOT);
            return property;
        }));
        return ajax;
    }
}
