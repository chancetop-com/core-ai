package ai.core.mcp.server.apiserver;

import ai.core.mcp.server.McpServerToolLoader;
import ai.core.api.apidefinition.ApiDefinition;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.function.Function;
import ai.core.utils.JsonSchemaUtil;
import core.framework.util.Strings;
import core.framework.web.exception.ConflictException;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class ApiMcpToolLoader implements McpServerToolLoader {

    public static String functionCallName(String app, String serviceName, String operationName) {
        return app.replaceAll("-", "_") + "_" + serviceName + "_" + operationName;
    }

    private final ApiLoader apiLoader;
    private DynamicApiCaller dynamicApiCaller;

    public ApiMcpToolLoader(ApiLoader apiLoader) {
        this.apiLoader = apiLoader;
    }

    @Override
    public List<ToolCall> load() {
        var apis = apiLoader.load();
        dynamicApiCaller = new DynamicApiCaller(apis);
        return apis.stream()
                .flatMap(api -> api.services.stream()
                        .flatMap(service -> service.operations.stream()
                                .map(operation -> new OperationContext(api, service, operation))))
                .map(ctx -> toToolCall(ctx.operation(), ctx.service(), ctx.api()))
                .toList();
    }

    @Override
    public List<String> defaultNamespaces() {
        return apiLoader.defaultNamespaces();
    }

    private ToolCall toToolCall(ApiDefinition.Operation operation, ApiDefinition.Service service, ApiDefinition api) {
        var method = Arrays.stream(DynamicApiCaller.class.getMethods()).filter(v -> v.getName().equals("callApi")).findFirst().orElseThrow();
        var params = operation.pathParams.stream().map(v -> toParamFromPathParam(v, api)).collect(Collectors.toList());
        if (operation.requestType != null) {
            params.addAll(toParamFromRequestType(operation.requestType, api));
        }
        return Function.builder()
                .namespace(api.app)
                .name(OperationContext.toFunctionCallName(operation, service, api))
                .description(operation.description)
                .object(dynamicApiCaller)
                .method(method)
                .needAuth(operation.needAuth)
                .dynamicArguments(true)
                .parameters(params).build();
    }

    private List<ToolCallParameter> toParamFromRequestType(String type, ApiDefinition api) {
        var builder = new ApiDefinitionTypeSchemaBuilder(api.types);
        var typeMap = api.types.stream().collect(Collectors.toMap(v -> v.name, java.util.function.Function.identity()));
        var requestType = typeMap.get(type);
        var params = builder.buildParameters(requestType);
        checkDuplicateName(params, type, api);
        return params;
    }

    private void checkDuplicateName(List<ToolCallParameter> params, String type, ApiDefinition api) {
        var names = new HashSet<String>();
        for (var param : params) {
            if (!names.add(param.getName())) {
                throw new ConflictException("DUPLICATE_PARAMETER_NAME_EXCEPTION", Strings.format("Not supported, duplicate parameter name: {}, api: {}, type: {}", param.getName(), api.app, type));
            }
        }
    }

    private ToolCallParameter toParamFromPathParam(ApiDefinition.PathParam pathParam, ApiDefinition api) {
        var param = new ToolCallParameter();
        param.setName(pathParam.name);
        param.setDescription(pathParam.description == null ? pathParam.name : pathParam.description);
        param.setRequired(true);
        if ("list".equalsIgnoreCase(pathParam.type)) {
            param.setItemType(ToolCallParameterType.valueOf(pathParam.type.toUpperCase(java.util.Locale.ROOT)).getType());
        }
        var type = ToolCallParameterType.STRING;
        if (isEnum(pathParam.type)) {
            var typeMap = api.types.stream().collect(Collectors.toMap(v -> v.name, java.util.function.Function.identity()));
            var e = typeMap.get(pathParam.type);
            param.setEnums(e.enumConstants.stream().map(v -> v.name).toList());
        } else {
            type = ToolCallParameterType.valueOf(pathParam.type.toUpperCase(java.util.Locale.ROOT));
        }
        param.setClassType(type.getType());
        param.setFormat(JsonSchemaUtil.buildJsonSchemaFormat(type));
        return param;
    }

    public boolean isEnum(String type) {
        return Arrays.stream(ToolCallParameterType.values()).noneMatch(v -> v.name().equalsIgnoreCase(type));
    }

    public record OperationContext(ApiDefinition api, ApiDefinition.Service service, ApiDefinition.Operation operation) {
        public static String toFunctionCallName(ApiDefinition.Operation operation, ApiDefinition.Service service, ApiDefinition api) {
            return functionCallName(api.app, service.name, operation.name);
        }
    }
}
