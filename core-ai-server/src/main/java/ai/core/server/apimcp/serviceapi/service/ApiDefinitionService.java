package ai.core.server.apimcp.serviceapi.service;

import ai.core.api.apidefinition.AiApiDefinition;
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
import ai.core.utils.JsonUtil;
import core.framework.inject.Inject;
import core.framework.log.ActionLogContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * @author stephen
 */
public class ApiDefinitionService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ApiDefinitionService.class);

    @Inject
    ServiceApiService apiService;

    public List<ApiDefinition> loadAll() {
        var apis = new ArrayList<ApiDefinition>();
        for (var serviceApi : apiService.findAll()) {
            if (!Boolean.TRUE.equals(serviceApi.enabled)) {
                continue;
            }
            try {
                apis.add(load(serviceApi));
            } catch (RuntimeException e) {
                LOGGER.error("failed to load api definition, name={}", serviceApi.name, e);
            }
        }
        return apis;
    }

    public ApiDefinition load(String name) {
        return load(apiService.getByName(name));
    }

    private ApiDefinition load(ServiceApi serviceApi) {
        ActionLogContext.put("load-api", serviceApi.name);
        var api = new ApiDefinition();
        var apiDefinition = loadDefinition(serviceApi.payload);
        api.app = apiDefinition.app;
        api.baseUrl = serviceApi.baseUrl;
        api.version = serviceApi.version;
        var serviceMap = toMap(serviceApi.serviceAdditional, v -> v.name);
        var typeMap = toMap(serviceApi.typeAdditional, v -> v.name);
        api.services = apiDefinition.services == null ? List.of() : apiDefinition.services.stream().filter(v -> {
            var apiService = getByName(v.name, serviceMap);
            return apiService == null || apiService.enabled;
        }).map(s -> toService(s, serviceMap)).toList();
        api.types = apiDefinition.types == null ? List.of() : apiDefinition.types.stream().map(t -> toType(t, typeMap)).toList();
        api.description = firstNonBlank(serviceApi.description, apiDefinition.description, firstServiceDescription(apiDefinition));
        return api;
    }

    private AiApiDefinition loadDefinition(String text) {
        return JsonUtil.fromJson(AiApiDefinition.class, text);
    }

    public SearchApiDefinitionResponse search(SearchApiDefinitionRequest request) {
        var apis = loadAll();
        var rsp = new SearchApiDefinitionResponse();
        rsp.apis = apis.stream().filter(v -> v.app.contains(request.app)).toList();
        return rsp;
    }

    private ApiDefinition.Service toService(AiApiDefinition.Service s, Map<String, ServiceAdditional> serviceMap) {
        var service = new ApiDefinition.Service();
        service.name = s.name;
        service.description = s.description;
        var apiService = getByName(s.name, serviceMap);
        if (apiService != null) {
            service.description = firstNonBlank(apiService.description, s.description);
        }
        var operationMap = apiService == null ? null : toMap(apiService.operationAdditional, v -> v.name);
        service.operations = s.operations == null ? List.of() : s.operations.stream().filter(v -> {
            if (operationMap == null) return true;
            var apiOperation = getByName(v.name, operationMap);
            return apiOperation == null || apiOperation.enabled;
        }).map(o -> toOperation(o, operationMap)).toList();
        return service;
    }

    private ApiDefinitionType toType(AiApiDefinition.Type t, Map<String, TypeAdditional> typeMap) {
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

    private ApiDefinition.Operation toOperation(AiApiDefinition.Operation o, Map<String, OperationAdditional> operationMap) {
        var operation = new ApiDefinition.Operation();
        operation.name = o.name;
        operation.method = o.method;
        operation.path = o.path;
        var apiOperation = getByName(o.name, operationMap);
        operation.description = firstNonBlank(apiOperation == null ? null : apiOperation.description, o.description, o.name);
        operation.example = firstNonBlank(apiOperation == null ? null : apiOperation.example, o.example);
        operation.needAuth = apiOperation == null ? null : apiOperation.needAuth;
        var paramMap = apiOperation == null ? null : toMap(apiOperation.pathParamAdditional, v -> v.name);
        operation.pathParams = o.pathParams == null ? List.of() : o.pathParams.stream().map(p -> toPathParam(p, paramMap)).toList();
        operation.deprecated = o.deprecated;
        operation.optional = o.optional;
        operation.requestType = o.requestType;
        operation.responseType = o.responseType;
        return operation;
    }

    private ApiDefinition.PathParam toPathParam(AiApiDefinition.PathParam p, Map<String, PathParamAdditional> paramMap) {
        var pathParam = new ApiDefinition.PathParam();
        pathParam.name = p.name;
        pathParam.type = p.type;
        var apiPathParam = getByName(p.name, paramMap);
        pathParam.description = firstNonBlank(apiPathParam == null ? null : apiPathParam.description, p.description);
        pathParam.example = firstNonBlank(apiPathParam == null ? null : apiPathParam.example, p.example);
        return pathParam;
    }

    private ApiDefinitionType.Field toField(AiApiDefinition.Field f, Map<String, FieldAdditional> fieldMap) {
        var field = new ApiDefinitionType.Field();
        field.name = f.name;
        field.type = f.type;
        field.constraints = new ApiDefinitionType.Constraints();
        field.typeParams = f.typeParams;
        var constraints = f.constraints == null ? new AiApiDefinition.Constraints() : f.constraints;
        field.constraints.max = constraints.max;
        field.constraints.min = constraints.min;
        field.constraints.notBlank = constraints.notBlank;
        field.constraints.notNull = constraints.notNull;
        field.constraints.pattern = constraints.pattern;
        field.constraints.size = new ApiDefinitionType.Size();
        field.constraints.size.min = constraints.size == null ? null : constraints.size.min;
        field.constraints.size.max = constraints.size == null ? null : constraints.size.max;
        var apiField = getByName(f.name, fieldMap);
        field.description = firstNonBlank(apiField == null ? null : apiField.description, f.description);
        field.example = firstNonBlank(apiField == null ? null : apiField.example, f.example);
        return field;
    }

    private String firstServiceDescription(AiApiDefinition definition) {
        if (definition.services == null) {
            return null;
        }
        for (var service : definition.services) {
            if (service.description != null && !service.description.isBlank()) {
                return service.description;
            }
        }
        return null;
    }

    private String firstNonBlank(String... values) {
        for (var value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private <T> T getByName(String name, Map<String, T> map) {
        return map == null ? null : map.get(name);
    }

    private <K, V> Map<K, V> toMap(java.util.Collection<V> collection, Function<V, K> keyMapper) {
        if (collection == null) return null;
        return collection.stream().collect(Collectors.toMap(keyMapper, Function.identity()));
    }
}
