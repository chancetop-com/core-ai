package ai.core.server.apimcp.serviceapi;

import ai.core.api.server.ApiDefinitionWebService;
import ai.core.api.server.ServiceApiWebService;
import ai.core.server.apimcp.serviceapi.api.ApiDefinitionWebServiceImpl;
import ai.core.server.apimcp.serviceapi.api.ServiceApiWebServiceImpl;
import ai.core.server.apimcp.serviceapi.service.ApiDefinitionService;
import ai.core.server.apimcp.serviceapi.service.MongoServiceApiStore;
import ai.core.server.apimcp.serviceapi.service.PgServiceApiStore;
import ai.core.server.apimcp.serviceapi.service.ServiceApiService;
import ai.core.server.apimcp.serviceapi.service.ServiceApiStore;
import core.framework.module.Module;

/**
 * @author stephen
 */
public class ServiceApiModule extends Module {
    @Override
    protected void initialize() {
        if ("pg".equals(property("sys.store.mode").orElse("mongo"))) {
            bind(ServiceApiStore.class, bind(PgServiceApiStore.class));
        } else {
            bind(ServiceApiStore.class, bind(MongoServiceApiStore.class));
        }
        bind(ServiceApiService.class);
        bind(ApiDefinitionService.class);
        api().service(ServiceApiWebService.class, bind(ServiceApiWebServiceImpl.class));
        api().service(ApiDefinitionWebService.class, bind(ApiDefinitionWebServiceImpl.class));
    }
}
