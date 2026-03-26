package ai.core.server.apimcp.serviceapi.api;

import ai.core.api.server.ServiceApiWebService;
import ai.core.api.server.serviceapi.CreateApiRequest;
import ai.core.api.server.serviceapi.ListServiceApiResponse;
import ai.core.api.server.serviceapi.ServiceApiView;
import ai.core.api.server.serviceapi.UpdateAllFromSysApiRequest;
import ai.core.api.server.serviceapi.UpdateApiRequest;
import ai.core.api.server.serviceapi.UpdateFromSysApiRequest;
import ai.core.server.apimcp.serviceapi.service.ServiceApiService;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class ServiceApiWebServiceImpl implements ServiceApiWebService {
    @Inject
    ServiceApiService serviceApiService;

    @Override
    public void create(CreateApiRequest request) {
        serviceApiService.create(request);
    }

    @Override
    public void delete(String id) {
        serviceApiService.delete(id);
    }

    @Override
    public void update(String id, UpdateApiRequest request) {
        serviceApiService.update(id, request);
    }

    @Override
    public void updateFromSysApi(String id, UpdateFromSysApiRequest request) {
        serviceApiService.updateFromSysApi(id, request.url, request.operator);
    }

    @Override
    public ServiceApiView get(String id) {
        return serviceApiService.get(id);
    }

    @Override
    public ListServiceApiResponse list() {
        return serviceApiService.list();
    }

    @Override
    public void updateAllFromSysApi(UpdateAllFromSysApiRequest request) {
        serviceApiService.updateAllFromSysApi(request.operator);
    }
}
