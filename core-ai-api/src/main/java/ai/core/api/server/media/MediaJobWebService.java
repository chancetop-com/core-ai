package ai.core.api.server.media;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface MediaJobWebService {
    @GET
    @Path("/api/media-jobs")
    ListMediaJobsResponse list(ListMediaJobsRequest request);
}
