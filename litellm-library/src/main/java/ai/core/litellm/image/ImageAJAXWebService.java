package ai.core.litellm.image;

import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface ImageAJAXWebService {
    @POST
    @Path("/images/generations")
    CreateImageAJAXResponse generate(CreateImageAJAXRequest request);
}
