package ai.core.server.apimcp.serviceapi.service;

import ai.core.api.apidefinition.AiApiDefinition;
import ai.core.api.server.serviceapi.CreateApiRequest;
import ai.core.api.server.serviceapi.FieldAdditionalView;
import ai.core.api.server.serviceapi.ListServiceApiResponse;
import ai.core.api.server.serviceapi.OperationAdditionalView;
import ai.core.api.server.serviceapi.PathParamAdditionalView;
import ai.core.api.server.serviceapi.ServiceAdditionalView;
import ai.core.api.server.serviceapi.ServiceApiView;
import ai.core.api.server.serviceapi.TypeAdditionalView;
import ai.core.api.server.serviceapi.UpdateApiRequest;
import ai.core.server.apimcp.serviceapi.domain.FieldAdditional;
import ai.core.server.apimcp.serviceapi.domain.OperationAdditional;
import ai.core.server.apimcp.serviceapi.domain.PathParamAdditional;
import ai.core.server.apimcp.serviceapi.domain.ServiceAdditional;
import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
import ai.core.server.apimcp.serviceapi.domain.TypeAdditional;
import ai.core.utils.JsonUtil;
import core.framework.async.Executor;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class ServiceApiService {
    private static final Logger LOGGER = LoggerFactory.getLogger(ServiceApiService.class);
    private static final String FORMAT_CORE_NG = "core-ng";
    private static final String FORMAT_CORE_AI = "core-ai";

    @Inject
    ServiceApiStore store;
    @Inject
    Executor executor;

    public void create(CreateApiRequest request) {
        var serviceApi = new ServiceApi();
        serviceApi.id = UUID.randomUUID().toString();
        serviceApi.name = request.name;
        serviceApi.version = UUID.randomUUID().toString();
        serviceApi.description = request.description;
        serviceApi.createdAt = ZonedDateTime.now();
        serviceApi.updatedAt = serviceApi.createdAt;
        serviceApi.createdBy = request.operator;
        serviceApi.updatedBy = serviceApi.createdBy;
        store.insert(serviceApi);
    }

    public void delete(String id) {
        store.delete(id);
    }

    public void update(String id, UpdateApiRequest request) {
        var serviceApi = store.get(id).orElseThrow(() -> new NotFoundException("service api not found: " + id));
        if (request.name != null) {
            serviceApi.name = request.name;
        }
        if (request.description != null) {
            serviceApi.description = request.description;
        }
        if (request.enabled != null) {
            serviceApi.enabled = request.enabled;
        }
        if (request.url != null) {
            serviceApi.url = request.url;
        }
        if (request.baseUrl != null) {
            serviceApi.baseUrl = request.baseUrl;
        }
        if (request.payload != null) {
            serviceApi.definitionFormat = definitionFormat(request.payload);
            serviceApi.payload = request.payload;
        }
        if (request.serviceAdditional != null) {
            serviceApi.serviceAdditional = request.serviceAdditional.stream().map(this::fromServiceView).toList();
        }
        if (request.typeAdditional != null) {
            serviceApi.typeAdditional = request.typeAdditional.stream().map(this::fromTypeView).toList();
        }
        serviceApi.updatedBy = request.operator;
        serviceApi.updatedAt = ZonedDateTime.now();
        store.replace(serviceApi);
    }

    public void updateFromSysApi(String id, String url, String operator) {
        var serviceApi = store.get(id).orElseThrow(() -> new NotFoundException("service api not found: " + id));
        var payload = fetchPayload(url);
        var format = definitionFormat(payload);
        serviceApi.url = url;
        serviceApi.payload = payload;
        serviceApi.definitionFormat = format;
        serviceApi.updatedBy = operator;
        serviceApi.updatedAt = ZonedDateTime.now();
        store.replace(serviceApi);
    }

    private String fetchPayload(String url) {
        var client = HTTPClient.builder()
                .trustAll()
                .connectTimeout(Duration.ofSeconds(5))
                .timeout(Duration.ofSeconds(30))
                .build();
        var req = new HTTPRequest(HTTPMethod.GET, url);
        req.headers.put("Accept", "application/json");
        var rsp = client.execute(req);
        if (rsp.statusCode < 200 || rsp.statusCode >= 300) {
            throw new BadRequestException("update from sys api failed, status=" + rsp.statusCode + ", url=" + url, "SYS_API_IMPORT_FAILED");
        }
        return rsp.text();
    }

    String definitionFormat(String payload) {
        AiApiDefinition definition;
        try {
            definition = JsonUtil.fromJson(AiApiDefinition.class, payload);
        } catch (RuntimeException e) {
            throw new BadRequestException("invalid api definition payload: " + e.getMessage(), "INVALID_API_DEFINITION", e);
        }
        if (definition.app == null || definition.app.isBlank()) {
            throw new BadRequestException("api definition app is missing", "INVALID_API_DEFINITION");
        }
        if (definition.services == null || definition.services.isEmpty()) {
            throw new BadRequestException("api definition services are missing", "INVALID_API_DEFINITION");
        }
        if (definition.schema == null || definition.schema.isBlank()) {
            return FORMAT_CORE_NG;
        }
        if (AiApiDefinition.SCHEMA_V1.equals(definition.schema)) {
            return FORMAT_CORE_AI;
        }
        throw new BadRequestException("unsupported api definition schema: " + definition.schema, "UNSUPPORTED_API_DEFINITION");
    }

    public void updateAllFromSysApi(String operator) {
        executor.submit("update-all-from-sys-api", () -> {
            var serviceApis = findAll();
            for (var serviceApi : serviceApis) {
                if (serviceApi.url == null) continue;
                try {
                    updateFromSysApi(serviceApi.id, serviceApi.url, operator);
                } catch (RuntimeException e) {
                    LOGGER.error("failed to update service api from sys api, id={}, name={}", serviceApi.id, serviceApi.name, e);
                }
            }
        });
    }

    public ServiceApi getByName(String name) {
        return store.getByName(name).orElseThrow(() -> new NotFoundException("service api not found: " + name));
    }

    public ServiceApiView get(String id) {
        return toApiView(store.get(id).orElseThrow(() -> new NotFoundException("service api not found: " + id)));
    }

    public ListServiceApiResponse list() {
        var rsp = new ListServiceApiResponse();
        rsp.serviceApis = findAll().stream().map(this::toApiView).toList();
        return rsp;
    }

    public List<ServiceApi> findAll() {
        return store.listAll();
    }

    /**
     * Returns a compact snapshot of all enabled service apis, used to detect configuration changes
     * so MCP api tools can be reloaded automatically without restarting the server.
     * Format: "{count}:{max(updatedAt)}", changes whenever any enabled record is added/updated/deleted.
     */
    public String configSnapshot() {
        int count = 0;
        ZonedDateTime maxUpdatedAt = null;
        for (var serviceApi : findAll()) {
            if (!Boolean.TRUE.equals(serviceApi.enabled)) continue;
            count++;
            if (serviceApi.updatedAt != null && (maxUpdatedAt == null || serviceApi.updatedAt.isAfter(maxUpdatedAt))) {
                maxUpdatedAt = serviceApi.updatedAt;
            }
        }
        return count + ":" + (maxUpdatedAt == null ? "" : maxUpdatedAt.toInstant());
    }

    private ServiceApiView toApiView(ServiceApi serviceApi) {
        var view = new ServiceApiView();
        view.id = serviceApi.id;
        view.name = serviceApi.name;
        view.enabled = serviceApi.enabled;
        view.description = serviceApi.description;
        view.baseUrl = serviceApi.baseUrl;
        view.url = serviceApi.url;
        view.version = serviceApi.version;
        view.definitionFormat = serviceApi.definitionFormat;
        view.payload = serviceApi.payload;
        view.createdAt = serviceApi.createdAt;
        view.updatedAt = serviceApi.updatedAt;
        view.createdBy = serviceApi.createdBy;
        view.updatedBy = serviceApi.updatedBy;
        if (serviceApi.serviceAdditional != null) {
            view.serviceAdditional = serviceApi.serviceAdditional.stream().map(this::toServiceView).toList();
        }
        if (serviceApi.typeAdditional != null) {
            view.typeAdditional = serviceApi.typeAdditional.stream().map(this::toTypeView).toList();
        }
        return view;
    }

    private TypeAdditionalView toTypeView(TypeAdditional additional) {
        var view = new TypeAdditionalView();
        view.name = additional.name;
        if (additional.fieldAdditional != null) {
            view.fieldAdditional = additional.fieldAdditional.stream().map(this::toFieldView).toList();
        }
        return view;
    }

    private ServiceAdditionalView toServiceView(ServiceAdditional additional) {
        var view = new ServiceAdditionalView();
        view.name = additional.name;
        view.description = additional.description;
        view.enabled = additional.enabled;
        if (additional.operationAdditional != null) {
            view.operationAdditional = additional.operationAdditional.stream().map(this::toOperationView).toList();
        }
        return view;
    }

    private FieldAdditionalView toFieldView(FieldAdditional additional) {
        var view = new FieldAdditionalView();
        view.name = additional.name;
        view.description = additional.description;
        view.example = additional.example;
        return view;
    }

    private OperationAdditionalView toOperationView(OperationAdditional additional) {
        var view = new OperationAdditionalView();
        view.name = additional.name;
        view.description = additional.description;
        view.example = additional.example;
        view.enabled = additional.enabled;
        view.needAuth = additional.needAuth;
        if (additional.pathParamAdditional != null) {
            view.pathParamAdditional = additional.pathParamAdditional.stream().map(this::toPathParamView).toList();
        }
        return view;
    }

    private PathParamAdditionalView toPathParamView(PathParamAdditional additional) {
        var view = new PathParamAdditionalView();
        view.name = additional.name;
        view.description = additional.description;
        view.example = additional.example;
        return view;
    }

    private ServiceAdditional fromServiceView(ServiceAdditionalView view) {
        var additional = new ServiceAdditional();
        additional.name = view.name;
        additional.description = view.description;
        if (view.enabled != null) {
            additional.enabled = view.enabled;
        }
        if (view.operationAdditional != null) {
            additional.operationAdditional = view.operationAdditional.stream().map(this::fromOperationView).toList();
        }
        return additional;
    }

    private TypeAdditional fromTypeView(TypeAdditionalView view) {
        var additional = new TypeAdditional();
        additional.name = view.name;
        if (view.fieldAdditional != null) {
            additional.fieldAdditional = view.fieldAdditional.stream().map(this::fromFieldView).toList();
        }
        return additional;
    }

    private OperationAdditional fromOperationView(OperationAdditionalView view) {
        var additional = new OperationAdditional();
        additional.name = view.name;
        additional.description = view.description;
        additional.example = view.example;
        additional.enabled = view.enabled != null && view.enabled;
        additional.needAuth = view.needAuth;
        if (view.pathParamAdditional != null) {
            additional.pathParamAdditional = view.pathParamAdditional.stream().map(this::fromPathParamView).toList();
        }
        return additional;
    }

    private PathParamAdditional fromPathParamView(PathParamAdditionalView view) {
        var additional = new PathParamAdditional();
        additional.name = view.name;
        additional.description = view.description;
        additional.example = view.example;
        return additional;
    }

    private FieldAdditional fromFieldView(FieldAdditionalView view) {
        var additional = new FieldAdditional();
        additional.name = view.name;
        additional.description = view.description;
        additional.example = view.example;
        return additional;
    }
}
