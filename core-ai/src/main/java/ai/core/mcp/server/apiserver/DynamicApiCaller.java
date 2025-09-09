package ai.core.mcp.server.apiserver;

import ai.core.mcp.server.apiserver.domain.ApiDefinition;
import ai.core.mcp.server.apiserver.domain.ApiDefinitionType;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.json.JSON;
import core.framework.log.ActionLogContext;
import core.framework.util.Strings;

import java.util.AbstractMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class DynamicApiCaller {
    private final Map<String, ApiDefinition.Operation> operationMap;
    private final Map<String, Map<String, ApiDefinitionType>> typeMap;
    private final Map<String, ApiDefinition> apiDefinitionMap;

    public DynamicApiCaller(List<ApiDefinition> apiDefinitions) {
        ActionLogContext.triggerTrace(true);
        this.operationMap = apiDefinitions.stream()
                .flatMap(api -> api.services.stream()
                        .flatMap(service -> service.operations.stream()
                                .map(operation -> new ApiMcpToolLoader.OperationContext(api, service, operation))))
                .collect(Collectors.toMap(v -> ApiMcpToolLoader.OperationContext.toFunctionCallName(v.operation(), v.service(), v.api()), ApiMcpToolLoader.OperationContext::operation));
        this.apiDefinitionMap = apiDefinitions.stream()
                .flatMap(api -> api.services.stream()
                        .flatMap(service -> service.operations.stream()
                                .map(operation -> new ApiMcpToolLoader.OperationContext(api, service, operation))))
                .collect(Collectors.toMap(v -> ApiMcpToolLoader.OperationContext.toFunctionCallName(v.operation(), v.service(), v.api()), ApiMcpToolLoader.OperationContext::api));
        this.typeMap = apiDefinitions.stream().collect(Collectors.toMap(v -> v.app, v -> v.types.stream().collect(Collectors.toMap(t -> t.name, Function.identity()))));
    }

    public String callApi(String name, String args) {
        ActionLogContext.put("mcp-call-api", name);
        ActionLogContext.put("mcp-call-api-args", args);
        var operation = operationMap.get(name);
        if (operation == null) {
            throw new IllegalArgumentException("Operation not found: " + name);
        }
        return call(name, operation, args);
    }

    @SuppressWarnings("unchecked")
    private String call(String name, ApiDefinition.Operation operation, String args) {
        ActionLogContext.triggerTrace(true);
        var argsMap = (Map<String, Object>) JsonUtil.fromJsonSafe(Map.class, args);
        ActionLogContext.put("mcp-call-api-args-map", argsMap);
        var apiDefinition = apiDefinitionMap.get(name);
        var url = apiDefinition.baseUrl + operation.path;
        var client = HTTPClient.builder().trustAll().build();
        for (var pathParam : operation.pathParams) {
            if (!argsMap.containsKey(pathParam.name)) {
                throw new IllegalArgumentException("Missing path parameter: " + pathParam.name);
            }
            url = url.replace(":" + pathParam.name, (String) argsMap.get(pathParam.name));
        }
        var req = new HTTPRequest(HTTPMethod.valueOf(operation.method), url);
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        ActionLogContext.put("mcp-call-api-url", url);
        if (operation.requestType != null) {
            var requestType = typeMap.get(apiDefinition.app).get(operation.requestType);
            if (requestType == null) {
                throw new IllegalArgumentException("Request type not found: " + operation.requestType);
            }
            if (req.method == HTTPMethod.GET || req.method == HTTPMethod.DELETE) {
                req.params.putAll(setupParams(apiDefinition, requestType, argsMap));
                req.uri = req.requestURI();
            } else {
                req.body(setupBody(apiDefinition, requestType, argsMap), ContentType.APPLICATION_JSON);
            }
        }
        try {
            ActionLogContext.put("mcp-call-api-req", JSON.toJSON(req));
            var rsp = client.execute(req);
            ActionLogContext.put("mcp-call-api-rsp", JSON.toJSON(rsp));
            return rsp.text();
        } catch (Exception e) {
            return Strings.format("Call api[{}, {}] failed: {}", url, JSON.toJSON(req), e.getMessage());
        }
    }

    private Map<String, String> setupParams(ApiDefinition apiDefinition, ApiDefinitionType type, Map<String, Object> argsMap) {
        var mapper = new ApiDefinitionTypeMapper(typeMap.get(apiDefinition.app).values().stream().toList());
        Map<String, AbstractMap.SimpleEntry<Object, Class<?>>> typedArgsMap = argsMap
                .entrySet()
                .stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> new AbstractMap.SimpleEntry<>(entry.getValue(), entry.getValue().getClass())
                ));
        var paramMap = mapper.buildParamsMap(type, typedArgsMap);
        return paramMap.entrySet().stream()
                .filter(entry -> entry.getValue() != null)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        entry -> QueryParamHelper.toString(entry.getValue().getKey(), entry.getValue().getValue())
                ));
    }

    private String setupBody(ApiDefinition apiDefinition, ApiDefinitionType type, Map<String, Object> argsMap) {
        var mapper = new ApiDefinitionTypeMapper(typeMap.get(apiDefinition.app).values().stream().toList());
        return JSON.toJSON(mapper.buildMap(type, argsMap));
    }
}
