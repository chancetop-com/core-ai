package ai.core.llm.providers;

import ai.core.agent.AgentRole;
import ai.core.api.mcp.JsonSchema;
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
import ai.core.llm.providers.inner.litellm.CreateCompletionAJAXRequest;
import ai.core.llm.providers.inner.litellm.CreateCompletionAJAXResponse;
import ai.core.llm.providers.inner.litellm.FunctionAJAXView;
import ai.core.llm.providers.inner.litellm.FunctionCallAJAXView;
import ai.core.llm.providers.inner.litellm.MessageAJAXView;
import ai.core.llm.providers.inner.litellm.ParameterAJAXView;
import ai.core.llm.providers.inner.litellm.PropertyAJAXView;
import ai.core.llm.providers.inner.litellm.RoleTypeAJAXView;
import ai.core.llm.providers.inner.litellm.ToolAJAXView;
import ai.core.llm.providers.inner.litellm.ToolTypeAJAXView;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.utils.JsonSchemaHelper;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.internal.json.JSONAnnotationIntrospector;
import core.framework.json.JSON;
import core.framework.util.Strings;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class LiteLLMProvider extends LLMProvider {
    private final String url;
    private final String token;
    private final ObjectMapper mapper;

    public LiteLLMProvider(LLMProviderConfig config, String url, String token) {
        super(config);
        this.url = url;
        this.token = token;
        this.mapper = new ObjectMapper();
        mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);
        mapper.setAnnotationIntrospector(new JSONAnnotationIntrospector());
    }

    @Override
    public CompletionResponse completion(CompletionRequest dto) {
        return toRsp(chatCompletion(toApiRequest(dto)), dto.name);
    }

    @Override
    public EmbeddingResponse embeddings(EmbeddingRequest dto) {
        return null;
    }

    @Override
    public CaptionImageResponse captionImage(CaptionImageRequest dto) {
        return null;
    }

    @Override
    public int maxTokens() {
        return 128 * 1000;
    }

    @Override
    public String name() {
        return "litellm";
    }

    public CreateCompletionAJAXResponse chatCompletion(CreateCompletionAJAXRequest request) {
        var client = HTTPClient.builder().trustAll().build();
        var req = new HTTPRequest(HTTPMethod.POST, url + "/chat/completions");
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        try {
            var body = mapper.writeValueAsString(request).getBytes(StandardCharsets.UTF_8);
            req.body(body, ContentType.APPLICATION_JSON);
            if (!Strings.isBlank(token)) {
                req.headers.put("Authorization", "Bearer " + token);
            }
            var rsp = client.execute(req);
            if (rsp.statusCode != 200) {
                throw new RuntimeException(rsp.text());
            }
            return JSON.fromJSON(CreateCompletionAJAXResponse.class, rsp.text());
        } catch (Exception e) {
            throw new RuntimeException("Failed to create completion: " + e.getMessage(), e);
        }
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

    private CreateCompletionAJAXRequest toApiRequest(CompletionRequest dto) {
        var apiReq = new CreateCompletionAJAXRequest();
        apiReq.model = Strings.isBlank(dto.model) ? config.getModel() : dto.model;
        if (!(dto.model.startsWith("o1") || dto.model.startsWith("o3-"))) {
            apiReq.temperature = dto.temperature != null ? dto.temperature : config.getTemperature();
        }
        apiReq.messages = dto.messages.stream().map(v -> {
            var message = new MessageAJAXView();
            message.role = RoleTypeAJAXView.valueOf(v.role.name());
            if (message.role == RoleTypeAJAXView.SYSTEM && dto.model.startsWith("o1")) {
                message.role = RoleTypeAJAXView.USER;
            }
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
            function.parameters = toParameter(v.getParameters(), "object", null);
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

    private ParameterAJAXView toParameter(List<ToolCallParameter> parameters, String propertyType, ToolCallParameter parent) {
        var ajax = new ParameterAJAXView();
        ajax.type = propertyType;
        if (parent != null) {
            ajax.enums = parent.getItemEnums();
        }
        ajax.required = parameters.stream().filter(ToolCallParameter::getRequired).map(ToolCallParameter::getName).toList();
        ajax.properties = parameters.stream().collect(Collectors.toMap(ToolCallParameter::getName, this::toProperty));
        return ajax;
    }

    private PropertyAJAXView toProperty(ToolCallParameter p) {
        var property = new PropertyAJAXView();
        property.description = p.getDescription();
        property.enums = p.getEnums();
        property.format = p.getFormat();
        property.type = JsonSchemaHelper.buildJsonSchemaType(p.getType()).name().toLowerCase(Locale.ROOT);
        if (p.getType().equals(List.class)) {
            if (p.getItems() != null && !p.getItems().isEmpty()) {
                property.items = toParameter(p.getItems(), toType(p.getItemType()).toLowerCase(Locale.ROOT), p);
            } else {
                property.items = new ParameterAJAXView();
                property.items.type = toType(p.getItemType()).toLowerCase(Locale.ROOT);
                property.enums = p.getItemEnums();
            }
        }
        return property;
    }

    private String toType(Class<?> c) {
        var n = c.getSimpleName().substring(c.getSimpleName().lastIndexOf('.') + 1).toUpperCase(Locale.ROOT);
        if ("object".equalsIgnoreCase(n)) {
            return "object";
        }
        var t = ToolCallParameterType.getByType(c);
        return buildJsonSchemaType(t);
    }

    private String buildJsonSchemaType(ToolCallParameterType p) {
        var supportType = ToolCallParameterType.getByType(p.getType());
        return switch (supportType) {
            case STRING, ZONEDDATETIME, LOCALDATE, LOCALDATETIME, LOCALTIME -> JsonSchema.PropertyType.STRING.name();
            case DOUBLE, BIGDECIMAL -> JsonSchema.PropertyType.NUMBER.name();
            case INTEGER, LONG -> JsonSchema.PropertyType.INTEGER.name();
            case LIST -> JsonSchema.PropertyType.ARRAY.name();
            case BOOLEAN -> JsonSchema.PropertyType.BOOLEAN.name();
            case MAP -> JsonSchema.PropertyType.OBJECT.name();
        };
    }
}
