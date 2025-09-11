package ai.core.mcp.server.apiserver;

import ai.core.utils.JsonSchemaUtil;
import ai.core.mcp.server.McpServerToolLoader;
import ai.core.api.apidefinition.ApiDefinition;
import ai.core.api.apidefinition.ApiDefinitionType;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.function.Function;
import core.framework.util.Lists;
import core.framework.util.Strings;
import core.framework.web.exception.ConflictException;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class ApiMcpToolLoader implements McpServerToolLoader {

    public static String functionCallName(String app, String serviceName, String operationName) {
        var name = app.replaceAll("-", "_") + "_" + serviceName + "_" + operationName;
        if (name.length() >= 64) {
            name = serviceName + "_" + operationName;
        }
        if (name.length() >= 64) {
            name = operationName;
        }
        return name;
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
        var typeMap = api.types.stream().collect(Collectors.toMap(v -> v.name, java.util.function.Function.identity()));
        var requestType = typeMap.get(type);
        List<ToolCallParameter> params = Lists.newArrayList();
        buildTypeParams(requestType, params, api);
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

    private void buildTypeParams(ApiDefinitionType requestType, List<ToolCallParameter> params, ApiDefinition api) {
        var typeMap = api.types.stream().collect(Collectors.toMap(v -> v.name, java.util.function.Function.identity()));
        var types = Arrays.stream(ToolCallParameterType.values()).map(v -> v.name().toUpperCase(Locale.ROOT)).collect(Collectors.toSet());
        for (var field : requestType.fields) {
            if (!types.contains(field.type.toUpperCase(Locale.ROOT)) && !typeMap.containsKey(field.type)) {
                throw new ConflictException("Unsupported type: " + field.type);
            }
            // todo support map
            if ("list".equalsIgnoreCase(field.type)) {
                params.add(toParamList(field, typeMap, api));
                continue;
            }
            if (typeMap.containsKey(field.type)) {
                var subType = typeMap.get(field.type);
                if ("enum".equalsIgnoreCase(subType.type)) {
                    params.add(toParamEnum(field, requestType, subType));
                } else {
                    var param = new ToolCallParameter();
                    param.setName(field.name);
                    param.setDescription(field.description == null ? field.name : field.description);
                    param.setRequired(field.constraints.notNull);
                    param.setClassType(Map.class);
                    param.setItemType(Object.class);
                    var subParams = new ArrayList<ToolCallParameter>();
                    buildTypeParams(typeMap.get(field.type), subParams, api);
                    param.setItems(subParams);
                    params.add(param);
                }
            } else {
                params.add(toParam(field));
            }
        }
    }

    private ToolCallParameter toParamList(ApiDefinitionType.Field field, Map<String, ApiDefinitionType> typeMap, ApiDefinition api) {
        var param = new ToolCallParameter();
        param.setName(field.name);
        param.setDescription(field.description == null ? field.name : field.description);
        param.setRequired(field.constraints.notNull);
        param.setClassType(List.class);
        var itemType = field.typeParams.getFirst();
        if (typeMap.containsKey(itemType)) {
            var subType = typeMap.get(field.typeParams.getFirst());
            if ("enum".equalsIgnoreCase(subType.type)) {
                param.setItemType(String.class);
                param.setItemEnums(toEnumConstants(subType));
            } else {
                param.setItemType(Object.class);
                List<ToolCallParameter> params = Lists.newArrayList();
                buildTypeParams(subType, params, api);
                param.setItems(params);
            }
        } else {
            var type = ToolCallParameterType.valueOf(itemType.toUpperCase(Locale.ROOT));
            param.setItemType(type.getType());
        }
        return param;
    }

    private ToolCallParameter toParamEnum(ApiDefinitionType.Field field, ApiDefinitionType requestType, ApiDefinitionType subType) {
        var param = new ToolCallParameter();
        param.setName(field.name);
        param.setDescription(requestType.name);
        param.setRequired(field.constraints.notNull);
        param.setClassType(String.class);
        param.setEnums(toEnumConstants(subType));
        return param;
    }

    private List<String> toEnumConstants(ApiDefinitionType requestType) {
        return requestType.enumConstants.stream().map(v -> v.name).toList();
    }

    private ToolCallParameter toParam(ApiDefinitionType.Field field) {
        var param = new ToolCallParameter();
        param.setName(field.name);
        param.setDescription(field.description == null ? field.name : field.description);
        param.setRequired(field.constraints.notNull);
        var type = ToolCallParameterType.valueOf(field.type.toUpperCase(Locale.ROOT));
        param.setClassType(type.getType());
        param.setFormat(JsonSchemaUtil.buildJsonSchemaFormat(type));
        return param;
    }

    private ToolCallParameter toParamFromPathParam(ApiDefinition.PathParam pathParam, ApiDefinition api) {
        var param = new ToolCallParameter();
        param.setName(pathParam.name);
        param.setDescription(pathParam.description == null ? pathParam.name : pathParam.description);
        param.setRequired(true);
        if ("list".equalsIgnoreCase(pathParam.type)) {
            param.setItemType(ToolCallParameterType.valueOf(pathParam.type.toUpperCase(Locale.ROOT)).getType());
        }
        var type = ToolCallParameterType.STRING;
        if (isEnum(pathParam.type)) {
            var typeMap = api.types.stream().collect(Collectors.toMap(v -> v.name, java.util.function.Function.identity()));
            var e = typeMap.get(pathParam.type);
            param.setEnums(e.enumConstants.stream().map(v -> v.name).toList());
        } else {
            type = ToolCallParameterType.valueOf(pathParam.type.toUpperCase(Locale.ROOT));
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
