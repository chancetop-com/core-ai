package ai.core.server;

import ai.core.api.server.apiuser.AdminApiUserWebService;
import ai.core.api.server.apiuser.ApiUserWebService;
import ai.core.server.apiuser.AdminApiUserWebServiceImpl;
import ai.core.server.apiuser.ApiUserKeyService;
import ai.core.server.apiuser.ApiUserQuotaService;
import ai.core.server.apiuser.ApiUserService;
import ai.core.server.apiuser.ApiUserUsageService;
import ai.core.server.apiuser.ApiUserWebServiceImpl;
import ai.core.server.apiuser.PermissionService;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ApiUserModule extends Module {
    @Override
    protected void initialize() {
        bind(ApiUserService.class);
        var keyService = bind(ApiUserKeyService.class);
        keyService.defaultTtlSeconds = property("sys.api-user.key.default.ttl").map(Integer::parseInt).orElse(3600);
        keyService.maxTtlSeconds = property("sys.api-user.key.max.ttl").map(Integer::parseInt).orElse(604800);
        bind(ApiUserQuotaService.class);
        bind(ApiUserUsageService.class);
        bind(PermissionService.class);

        api().service(ApiUserWebService.class, bind(ApiUserWebServiceImpl.class));
        api().service(AdminApiUserWebService.class, bind(AdminApiUserWebServiceImpl.class));
    }
}
