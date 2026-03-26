package ai.core.server.apimcp.serviceapi.service;

import ai.core.api.apidefinition.ApiDefinition;
import ai.core.api.apidefinition.ApiDefinitionType;
import ai.core.api.server.apidefinition.SearchApiDefinitionRequest;
import ai.core.api.server.apidefinition.SearchApiDefinitionResponse;
import ai.core.server.apimcp.serviceapi.domain.FieldAdditional;
import ai.core.server.apimcp.serviceapi.domain.OperationAdditional;
import ai.core.server.apimcp.serviceapi.domain.PathParamAdditional;
import ai.core.server.apimcp.serviceapi.domain.ServiceAdditional;
import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
import ai.core.server.apimcp.serviceapi.domain.TypeAdditional;
import core.framework.inject.Inject;
import core.framework.internal.web.api.APIDefinitionResponse;
import core.framework.internal.web.api.APIType;
import core.framework.json.JSON;
import core.framework.log.ActionLogContext;

import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class ApiDefinitionService {
    @Inject
    ServiceApiService apiService;

    public List<ApiDefinition> loadAll() {
        return apiService.findAll().stream().filter(v -> v.enabled).map(this::load).toList();
    }

    public ApiDefinition load(String name) {
        return load(apiService.getByName(name));
    }

    private ApiDefinition load(ServiceApi serviceApi) {
        ActionLogContext.put("load-api", serviceApi.name);
        var api = new ApiDefinition();
        var apiDefinition = loadCoreApi(serviceApi.payload);
        api.app = apiDefinition.app;
        api.baseUrl = serviceApi.baseUrl;
        api.version = serviceApi.version;
        var serviceMap = toMap(serviceApi.serviceAdditional, v -> v.name);
        var typeMap = toMap(serviceApi.typeAdditional, v -> v.name);
        api.services = apiDefinition.services.stream().filter(v -> {
            var apiService = getByName(v.name, serviceMap);
            return apiService == null || apiService.enabled;
        }).map(s -> toService(s, serviceMap)).toList();
        api.types = apiDefinition.types.stream().map(t -> toType(t, typeMap)).toList();
        return api;
    }

    private APIDefinitionResponse loadCoreApi(String text) {
        return JSON.fromJSON(APIDefinitionResponse.class, text);
    }

    public SearchApiDefinitionResponse search(SearchApiDefinitionRequest request) {
        var apis = loadAll();
        var rsp = new SearchApiDefinitionResponse();
        rsp.apis = apis.stream().filter(v -> v.app.contains(request.app)).toList();
        return rsp;
    }

    private ApiDefinition.Service toService(APIDefinitionResponse.Service s, Map<String, ServiceAdditional> serviceMap) {
        var service = new ApiDefinition.Service();
        service.name = s.name;
        var apiService = getByName(s.name, serviceMap);
        if (apiService != null) {
            service.description = apiService.description;
        }
        var operationMap = apiService == null ? null : toMap(apiService.operationAdditional, v -> v.name);
        service.operations = s.operations.stream().filter(v -> {
            if (operationMap == null) return true;
            var apiOperation = getByName(v.name, operationMap);
            return apiOperation == null || apiOperation.enabled;
        }).map(o -> toOperation(o, operationMap)).toList();
        return service;
    }

    private ApiDefinitionType toType(APIType t, Map<String, TypeAdditional> typeMap) {
        var type = new ApiDefinitionType();
        type.name = t.name;
        type.type = t.type;
        type.enumConstants = t.enumConstants == null ? null : t.enumConstants.stream().map(e -> {
            var enumConstant = new ApiDefinitionType.EnumConstant();
            enumConstant.name = e.name;
            enumConstant.value = e.value;
            return enumConstant;
        }).toList();
        var apiType = getByName(t.name, typeMap);
        var fieldMap = apiType == null ? null : toMap(apiType.fieldAdditional, v -> v.name);
        type.fields = t.fields == null ? List.of() : t.fields.stream().map(f -> toField(f, fieldMap)).toList();
        return type;
    }

    private ApiDefinition.Operation toOperation(APIDefinitionResponse.Operation o, Map<String, OperationAdditional> operationMap) {
        var operation = new ApiDefinition.Operation();
        operation.name = o.name;
        operation.method = o.method;
        operation.path = o.path;
        var apiOperation = getByName(o.name, operationMap);
        if (apiOperation != null) {
            operation.description = apiOperation.description;
            operation.example = apiOperation.example;
            operation.needAuth = apiOperation.needAuth;
        }
        if (operation.description == null) {
            operation.description = operation.name;
        }
        var paramMap = apiOperation == null ? null : toMap(apiOperation.pathParamAdditional, v -> v.name);
        operation.pathParams = o.pathParams.stream().map(p -> toPathParam(p, paramMap)).toList();
        operation.deprecated = o.deprecated;
        operation.optional = o.optional;
        operation.requestType = o.requestType;
        operation.responseType = o.responseType;
        return operation;
    }

    private ApiDefinition.PathParam toPathParam(APIDefinitionResponse.PathParam p, Map<String, PathParamAdditional> paramMap) {
        var pathParam = new ApiDefinition.PathParam();
        pathParam.name = p.name;
        pathParam.type = p.type;
        var apiPathParam = getByName(p.name, paramMap);
        if (apiPathParam != null) {
            pathParam.description = apiPathParam.description;
            pathParam.example = apiPathParam.example;
        }
        return pathParam;
    }

    private ApiDefinitionType.Field toField(APIType.Field f, Map<String, FieldAdditional> fieldMap) {
        var field = new ApiDefinitionType.Field();
        field.name = f.name;
        field.type = f.type;
        field.constraints = new ApiDefinitionType.Constraints();
        field.typeParams = f.typeParams;
        field.constraints.max = f.constraints.max;
        field.constraints.min = f.constraints.min;
        field.constraints.notBlank = f.constraints.notBlank;
        field.constraints.notNull = f.constraints.notNull;
        field.constraints.pattern = f.constraints.pattern;
        field.constraints.size = new ApiDefinitionType.Size();
        field.constraints.size.min = f.constraints.size == null ? null : f.constraints.size.min;
        field.constraints.size.max = f.constraints.size == null ? null : f.constraints.size.max;
        var apiField = getByName(f.name, fieldMap);
        if (apiField != null) {
            field.description = apiField.description;
            field.example = apiField.example;
        }
        return field;
    }

    private <T> T getByName(String name, Map<String, T> map) {
        return map == null ? null : map.get(name);
    }

    private <K, V> Map<K, V> toMap(java.util.Collection<V> collection, Function<V, K> keyMapper) {
        if (collection == null) return null;
        return collection.stream().collect(Collectors.toMap(keyMapper, Function.identity()));
    }
}
