package ai.core.api.server.gateway;

import core.framework.api.web.service.QueryParam;

/**
 * @author stephen
 */
public class DeleteGatewayProviderRequest {
    @QueryParam(name = "cascade")
    public String cascade;
}
