package ai.core.server.apimcp.serviceapi.service;

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
import com.mongodb.client.model.Filters;
import core.framework.async.Executor;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.NotFoundException;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

/**
 * @author stephen
 */
public class ServiceApiService {
    @Inject
    MongoCollection<ServiceApi> apiMongoCollection;
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
        apiMongoCollection.insert(serviceApi);
    }

    public void delete(String id) {
        apiMongoCollection.delete(id);
    }

    public void update(String id, UpdateApiRequest request) {
        var serviceApi = apiMongoCollection.get(id).orElseThrow(() -> new NotFoundException("service api not found: " + id));
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
        apiMongoCollection.replace(serviceApi);
    }

    public void updateFromSysApi(String id, String url, String operator) {
        var serviceApi = apiMongoCollection.get(id).orElseThrow(() -> new NotFoundException("service api not found: " + id));
        var client = HTTPClient.builder().trustAll().build();
        var req = new HTTPRequest(HTTPMethod.GET, url);
        req.headers.put("Content-Type", "application/json");
        var rsp = client.execute(req);
        serviceApi.url = url;
        serviceApi.payload = rsp.text();
        serviceApi.updatedBy = operator;
        serviceApi.updatedAt = ZonedDateTime.now();
        apiMongoCollection.replace(serviceApi);
    }

    public void updateAllFromSysApi(String operator) {
        executor.submit("update-all-from-sys-api", () -> {
            var serviceApis = findAll();
            for (var serviceApi : serviceApis) {
                if (serviceApi.url == null) continue;
                updateFromSysApi(serviceApi.id, serviceApi.url, operator);
            }
        });
    }

    public ServiceApi getByName(String name) {
        return apiMongoCollection.findOne(Filters.eq("name", name)).orElseThrow(() -> new NotFoundException("service api not found: " + name));
    }

    public ServiceApiView get(String id) {
        return toApiView(apiMongoCollection.get(id).orElseThrow(() -> new NotFoundException("service api not found: " + id)));
    }

    public ListServiceApiResponse list() {
        var rsp = new ListServiceApiResponse();
        rsp.serviceApis = findAll().stream().map(this::toApiView).toList();
        return rsp;
    }

    public List<ServiceApi> findAll() {
        return apiMongoCollection.find(Filters.empty());
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
