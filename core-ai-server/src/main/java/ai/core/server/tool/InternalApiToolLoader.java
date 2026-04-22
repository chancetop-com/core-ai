package ai.core.server.tool;

import ai.core.api.apidefinition.ApiDefinition;
import ai.core.mcp.server.apiserver.DynamicApiCaller;
import ai.core.server.apimcp.serviceapi.service.ApiDefinitionService;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameter;
import ai.core.tool.ToolCallParameterType;
import ai.core.tool.function.Function;
import ai.core.utils.JsonSchemaUtil;
import core.framework.inject.Inject;
import core.framework.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class InternalApiToolLoader {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalApiToolLoader.class);

    public static final String API_APP_PREFIX = "api-app:";
    public static final String API_SERVICE_PREFIX = "api-service:";
    public static final String API_OPERATION_PREFIX = "api-operation:";

    public static boolean isApiToolId(String toolId) {
        return toolId != null && (toolId.startsWith(API_APP_PREFIX) || toolId.startsWith(API_SERVICE_PREFIX) || toolId.startsWith(API_OPERATION_PREFIX));
    }

    @Inject
    ApiDefinitionService apiDefinitionService;

    private final Map<String, DynamicApiCaller> callerCache = new HashMap<>();

    public InternalApiToolLoader() {
    }

    public InternalApiToolLoader(ApiDefinitionService apiDefinitionService) {
        this.apiDefinitionService = apiDefinitionService;
    }

    public List<ApiAppInfo> listApiApps() {
        if (apiDefinitionService == null) {
            return List.of();
        }
        return apiDefinitionService.loadAll().stream()
                .map(api -> {
                    String description = null;
                    if (api.services != null && !api.services.isEmpty()) {
                        description = api.services.get(0).description;
                    }
                    return new ApiAppInfo(api.app, api.baseUrl, api.version, description);
                })
                .toList();
    }

    public List<ToolCall> load() {
        if (apiDefinitionService == null) {
            LOGGER.warn("ApiDefinitionService not available");
            return List.of();
        }
        var apis = apiDefinitionService.loadAll();
        if (apis.isEmpty()) {
            LOGGER.debug("No API definitions found");
            return List.of();
        }
        return loadTools(apis);
    }

    public List<ToolCall> loadApiAppTools(String appName) {
        if (apiDefinitionService == null) {
            LOGGER.warn("ApiDefinitionService not available");
            return List.of();
        }

        var apis = apiDefinitionService.loadAll().stream()
                .filter(api -> api.app.equals(appName))
                .toList();

        if (apis.isEmpty()) {
            LOGGER.debug("No API definition found for app: {}", appName);
            return List.of();
        }

        return loadTools(apis);
    }

    public List<ToolCall> loadApiServiceTools(String appName, String serviceName) {
        if (apiDefinitionService == null) {
            LOGGER.warn("ApiDefinitionService not available");
            return List.of();
        }

        var apis = apiDefinitionService.loadAll().stream()
                .filter(api -> api.app.equals(appName))
                .toList();

        if (apis.isEmpty()) {
            LOGGER.debug("No API definition found for app: {}", appName);
            return List.of();
        }

        var filteredApis = apis.stream()
                .map(api -> {
                    var filtered = new ApiDefinition();
                    filtered.app = api.app;
                    filtered.baseUrl = api.baseUrl;
                    filtered.version = api.version;
                    filtered.services = api.services.stream()
                            .filter(s -> s.name.equals(serviceName))
                            .toList();
                    filtered.types = api.types;
                    return filtered;
                })
                .filter(api -> !api.services.isEmpty())
                .toList();

        return loadTools(filteredApis);
    }

    public List<ToolCall> loadApiOperationTools(String appName, String serviceName, String operationName) {
        if (apiDefinitionService == null) {
            LOGGER.warn("ApiDefinitionService not available");
            return List.of();
        }

        var apis = apiDefinitionService.loadAll().stream()
                .filter(api -> api.app.equals(appName))
                .toList();

        if (apis.isEmpty()) {
            LOGGER.debug("No API definition found for app: {}", appName);
            return List.of();
        }

        var filteredApis = apis.stream()
                .map(api -> {
                    var filtered = new ApiDefinition();
                    filtered.app = api.app;
                    filtered.baseUrl = api.baseUrl;
                    filtered.version = api.version;
                    filtered.services = api.services.stream()
                            .filter(s -> s.name.equals(serviceName))
                            .map(s -> {
                                var svc = new ApiDefinition.Service();
                                svc.name = s.name;
                                svc.description = s.description;
                                svc.operations = s.operations.stream()
                                        .filter(op -> op.name.equals(operationName))
                                        .toList();
                                return svc;
                            })
                            .filter(s -> !s.operations.isEmpty())
                            .toList();
                    filtered.types = api.types;
                    return filtered;
                })
                .filter(api -> !api.services.isEmpty())
                .toList();

        return loadTools(filteredApis);
    }

    public List<ToolCall> loadByToolId(String toolId) {
        if (toolId.startsWith(API_APP_PREFIX)) {
            var appName = toolId.substring(API_APP_PREFIX.length());
            return loadApiAppTools(appName);
        } else if (toolId.startsWith(API_OPERATION_PREFIX)) {
            var remaining = toolId.substring(API_OPERATION_PREFIX.length());
            var parts = remaining.split(":", 3);
            if (parts.length == 3) {
                return loadApiOperationTools(parts[0], parts[1], parts[2]);
            }
        } else if (toolId.startsWith(API_SERVICE_PREFIX)) {
            var remaining = toolId.substring(API_SERVICE_PREFIX.length());
            var colonIdx = remaining.indexOf(':');
            if (colonIdx > 0) {
                var appName = remaining.substring(0, colonIdx);
                var serviceName = remaining.substring(colonIdx + 1);
                return loadApiServiceTools(appName, serviceName);
            }
        }
        LOGGER.warn("Unknown API tool ID pattern: {}", toolId);
        return List.of();
    }

    public List<ApiServiceInfo> listApiAppServices(String appName) {
        if (apiDefinitionService == null) return List.of();
        return apiDefinitionService.loadAll().stream()
                .filter(api -> api.app.equals(appName))
                .flatMap(api -> api.services.stream())
                .map(s -> new ApiServiceInfo(
                        s.name,
                        s.description,
                        s.operations != null ? s.operations.size() : 0,
                        s.operations != null ? s.operations.stream()
                                .map(op -> new ApiOperationInfo(op.name, op.description, op.method, op.path))
                                .toList() : List.of()))
                .toList();
    }

    private List<ToolCall> loadTools(List<ApiDefinition> apis) {
        if (apis.isEmpty()) {
            return List.of();
        }

        var caller = new DynamicApiCaller(apis);
        var appNames = apis.stream().map(a -> a.app).collect(Collectors.joining(","));
        callerCache.put(appNames, caller);

        var tools = apis.stream()
                .flatMap(api -> api.services.stream()
                        .flatMap(service -> service.operations.stream()
                                .map(operation -> toToolCall(caller, api, service, operation))))
                .toList();

        LOGGER.info("Loaded {} API tools from {} API definitions", tools.size(), apis.size());
        return tools;
    }

    private ToolCall toToolCall(DynamicApiCaller caller, ApiDefinition api, ApiDefinition.Service service, ApiDefinition.Operation operation) {
        var method = findCallApiMethod();
        var params = operation.pathParams.stream()
                .map(p -> toParamFromPathParam(p, api))
                .collect(Collectors.toList());

        if (operation.requestType != null) {
            params.addAll(toParamFromRequestType(operation.requestType, api));
        }

        return Function.builder()
                .namespace(api.app)
                .name(functionCallName(api.app, service.name, operation.name))
                .description(operation.description != null ? operation.description : operation.name)
                .object(caller)
                .method(method)
                .needAuth(operation.needAuth)
                .dynamicArguments(true)
                .parameters(params)
                .build();
    }

    private String functionCallName(String app, String serviceName, String operationName) {
        return app.replaceAll("-", "_") + "_" + serviceName + "_" + operationName;
    }

    private Method findCallApiMethod() {
        return Arrays.stream(DynamicApiCaller.class.getMethods())
                .filter(m -> m.getName().equals("callApi"))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("callApi method not found in DynamicApiCaller"));
    }

    private List<ToolCallParameter> toParamFromRequestType(String type, ApiDefinition api) {
        var builder = new ai.core.mcp.server.apiserver.ApiDefinitionTypeSchemaBuilder(api.types);
        var typeMap = api.types.stream().collect(Collectors.toMap(v -> v.name, java.util.function.Function.identity()));
        var requestType = typeMap.get(type);
        if (requestType == null) {
            LOGGER.warn("Request type not found: {} in API: {}", type, api.app);
            return List.of();
        }
        var params = builder.buildParameters(requestType);
        checkDuplicateName(params, type, api);
        return params;
    }

    private void checkDuplicateName(List<ToolCallParameter> params, String type, ApiDefinition api) {
        var names = new HashSet<String>();
        for (var param : params) {
            if (!names.add(param.getName())) {
                LOGGER.warn(Strings.format("Duplicate parameter name: {} in API: {}, type: {}", param.getName(), api.app, type));
            }
        }
    }

    private ToolCallParameter toParamFromPathParam(ApiDefinition.PathParam pathParam, ApiDefinition api) {
        var param = new ToolCallParameter();
        param.setName(pathParam.name);
        param.setDescription(pathParam.description != null ? pathParam.description : pathParam.name);
        param.setRequired(true);

        var type = ToolCallParameterType.STRING;
        if (isEnum(pathParam.type)) {
            var typeMap = api.types.stream().collect(Collectors.toMap(v -> v.name, java.util.function.Function.identity()));
            var enumType = typeMap.get(pathParam.type);
            if (enumType != null && enumType.enumConstants != null) {
                param.setEnums(enumType.enumConstants.stream().map(e -> e.name).toList());
            }
        } else if ("list".equalsIgnoreCase(pathParam.type)) {
            param.setItemType(ToolCallParameterType.valueOf(pathParam.type.toUpperCase(Locale.ROOT)).getType());
        }

        if (isPrimitiveType(pathParam.type)) {
            type = ToolCallParameterType.valueOf(pathParam.type.toUpperCase(Locale.ROOT));
        }

        param.setClassType(type.getType());
        param.setFormat(JsonSchemaUtil.buildJsonSchemaFormat(type));
        return param;
    }

    private boolean isEnum(String type) {
        if (type == null) return false;
        return Arrays.stream(ToolCallParameterType.values())
                .noneMatch(v -> v.name().equalsIgnoreCase(type));
    }

    private boolean isPrimitiveType(String type) {
        if (type == null) return false;
        try {
            ToolCallParameterType.valueOf(type.toUpperCase(Locale.ROOT));
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    public record ApiAppInfo(String app, String baseUrl, String version, String description) {
    }

    public record ApiServiceInfo(String name, String description, int operationCount, List<ApiOperationInfo> operations) {
    }

    public record ApiOperationInfo(String name, String description, String method, String path) {
    }
}
