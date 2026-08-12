package ai.core.server.apimcp.serviceapi.api;

import ai.core.api.server.ServiceApiWebService;
import ai.core.api.server.serviceapi.CreateApiRequest;
import ai.core.api.server.serviceapi.ListServiceApiResponse;
import ai.core.api.server.serviceapi.ServiceApiView;
import ai.core.api.server.serviceapi.UpdateAllFromSysApiRequest;
import ai.core.api.server.serviceapi.UpdateApiRequest;
import ai.core.api.server.serviceapi.UpdateFromSysApiRequest;
import ai.core.server.apimcp.serviceapi.service.ServiceApiService;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import core.framework.inject.Inject;

/**
 * @author stephen
 */
public class ServiceApiWebServiceImpl implements ServiceApiWebService {
    @Inject
    ServiceApiService serviceApiService;

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_MANAGE)
    public void create(CreateApiRequest request) {
        serviceApiService.create(request);
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_MANAGE)
    public void delete(String id) {
        serviceApiService.delete(id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_MANAGE)
    public void update(String id, UpdateApiRequest request) {
        serviceApiService.update(id, request);
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_MANAGE)
    public void updateFromSysApi(String id, UpdateFromSysApiRequest request) {
        serviceApiService.updateFromSysApi(id, request.url, request.operator);
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_VIEW)
    public ServiceApiView get(String id) {
        return serviceApiService.get(id);
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_VIEW)
    public ListServiceApiResponse list() {
        return serviceApiService.list();
    }

    @Override
    @PermissionsRequired(PermissionCodes.APITOOL_MANAGE)
    public void updateAllFromSysApi(UpdateAllFromSysApiRequest request) {
        serviceApiService.updateAllFromSysApi(request.operator);
    }
}
