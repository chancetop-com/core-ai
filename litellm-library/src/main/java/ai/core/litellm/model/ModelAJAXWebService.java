package ai.core.litellm.model;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface ModelAJAXWebService {
    @GET
    @Path("/models")
    ListModelAJAXResponse list();

    @GET
    @Path("/model/info")
    GetModelAJAXResponse info();
}
