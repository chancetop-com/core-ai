package ai.core.api.server.serviceapi;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListServiceApiResponse {
    @Property(name = "service_apis")
    public List<ServiceApiView> serviceApis;
}
