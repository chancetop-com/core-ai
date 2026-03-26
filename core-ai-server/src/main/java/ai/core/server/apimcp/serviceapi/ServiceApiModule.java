package ai.core.server.apimcp.serviceapi;

import ai.core.api.server.ApiDefinitionWebService;
import ai.core.api.server.ServiceApiWebService;
import ai.core.server.apimcp.serviceapi.api.ApiDefinitionWebServiceImpl;
import ai.core.server.apimcp.serviceapi.api.ServiceApiWebServiceImpl;
import ai.core.server.apimcp.serviceapi.domain.ServiceApi;
import ai.core.server.apimcp.serviceapi.service.ApiDefinitionService;
import ai.core.server.apimcp.serviceapi.service.ServiceApiService;
import core.framework.module.Module;
import core.framework.mongo.module.MongoConfig;

/**
 * @author stephen
 */
public class ServiceApiModule extends Module {
    @Override
    protected void initialize() {
        var config = config(MongoConfig.class);
        config.uri(requiredProperty("sys.mongo.uri"));
        config.collection(ServiceApi.class);
        bind(ServiceApiService.class);
        bind(ApiDefinitionService.class);
        api().service(ServiceApiWebService.class, bind(ServiceApiWebServiceImpl.class));
        api().service(ApiDefinitionWebService.class, bind(ApiDefinitionWebServiceImpl.class));
    }
}
