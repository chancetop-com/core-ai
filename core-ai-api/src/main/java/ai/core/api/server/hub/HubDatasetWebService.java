package ai.core.api.server.hub;

import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Session-anchored dataset access over the plain user credentials (local CLI / SDK backends), the
 * counterpart of the sandbox hub surface for scripts that run outside a sandbox.
 * <p>
 * One session is the whole scope: a caller can only reach the datasets bound to that session's agent,
 * with the same permissions and the same payload text the builtin tools produce. Record/state data is
 * passed as JSON object text, mirroring {@link ai.core.api.server.mcphub.HubCallRequest}.
 *
 * @author stephen
 */
public interface HubDatasetWebService {
    @GET
    @Path("/api/hub/sessions/:sessionId/datasets")
    HubDatasetListView list(@PathParam("sessionId") String sessionId);

    @GET
    @Path("/api/hub/sessions/:sessionId/datasets/:datasetId/state")
    HubDatasetOpResponse getState(@PathParam("sessionId") String sessionId, @PathParam("datasetId") String datasetId,
                                  HubDatasetStateQuery query);

    @PUT
    @Path("/api/hub/sessions/:sessionId/datasets/:datasetId/state")
    HubDatasetOpResponse saveState(@PathParam("sessionId") String sessionId, @PathParam("datasetId") String datasetId,
                                   HubDatasetDataRequest request);

    @POST
    @Path("/api/hub/sessions/:sessionId/datasets/:datasetId/state/patch")
    HubDatasetOpResponse patchState(@PathParam("sessionId") String sessionId, @PathParam("datasetId") String datasetId,
                                    HubDatasetDataRequest request);

    @GET
    @Path("/api/hub/sessions/:sessionId/datasets/:datasetId/records")
    HubDatasetOpResponse queryRecords(@PathParam("sessionId") String sessionId, @PathParam("datasetId") String datasetId,
                                      HubDatasetRecordsQuery query);

    @POST
    @Path("/api/hub/sessions/:sessionId/datasets/:datasetId/records")
    HubDatasetOpResponse insertRecord(@PathParam("sessionId") String sessionId, @PathParam("datasetId") String datasetId,
                                      HubDatasetDataRequest request);

    @POST
    @Path("/api/hub/sessions/:sessionId/datasets/:datasetId/records/:recordId")
    HubDatasetOpResponse updateRecord(@PathParam("sessionId") String sessionId, @PathParam("datasetId") String datasetId,
                                      @PathParam("recordId") String recordId, HubDatasetDataRequest request);

    @DELETE
    @Path("/api/hub/sessions/:sessionId/datasets/:datasetId/records/:recordId")
    HubDatasetOpResponse deleteRecord(@PathParam("sessionId") String sessionId, @PathParam("datasetId") String datasetId,
                                      @PathParam("recordId") String recordId);
}
