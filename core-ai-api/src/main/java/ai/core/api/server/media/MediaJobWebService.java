package ai.core.api.server.media;

import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;

/**
 * @author stephen
 */
public interface MediaJobWebService {
    @GET
    @Path("/api/media-jobs")
    ListMediaJobsResponse list(ListMediaJobsRequest request);

    @GET
    @Path("/api/media-jobs/compare-models")
    ListImageCompareModelsResponse compareModels();

    @POST
    @Path("/api/media-jobs/compare")
    ImageCompareRunResponse compare(ImageCompareRunRequest request);
}
