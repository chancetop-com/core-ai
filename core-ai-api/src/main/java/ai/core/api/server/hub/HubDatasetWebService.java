package ai.core.api.server.hub;

import ai.core.api.server.dataset.ListDatasetsRequest;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;

/**
 * Dataset access over the plain user credentials (local CLI / SDK backends), the counterpart of the
 * sandbox hub surface for scripts that run outside a sandbox.
 * <p>
 * Two scopes share one contract. {@code /api/hub/sessions/:sessionId/datasets/*} is session-anchored:
 * a caller reaches only the datasets bound to that session's agent, with the binding's permission.
 * {@code /api/hub/datasets/*} addresses a dataset directly: it is the data-maintenance scope for
 * GENERAL datasets, authorized by the caller's own {@code dataset.view} / {@code dataset.manage}
 * permissions, and it refuses SESSION datasets whose data only exists per session. Both scopes return
 * the payload text the builtin tools produce. Record/state data is passed as JSON object text,
 * mirroring {@link ai.core.api.server.mcphub.HubCallRequest}.
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

    @POST
    @Path("/api/hub/sessions/:sessionId/datasets/:datasetId/fields/rename")
    HubDatasetOpResponse renameField(@PathParam("sessionId") String sessionId, @PathParam("datasetId") String datasetId,
                                     HubDatasetRenameRequest request);

    // ---------- dataset scope: no session, authorized by the caller's dataset permissions ----------

    @GET
    @Path("/api/hub/datasets")
    HubDatasetListView listAll(ListDatasetsRequest request);

    @GET
    @Path("/api/hub/datasets/:datasetId")
    HubDatasetView getDataset(@PathParam("datasetId") String datasetId);

    @GET
    @Path("/api/hub/datasets/:datasetId/records")
    HubDatasetOpResponse queryDatasetRecords(@PathParam("datasetId") String datasetId, HubDatasetRecordsQuery query);

    @POST
    @Path("/api/hub/datasets/:datasetId/records")
    HubDatasetOpResponse insertDatasetRecord(@PathParam("datasetId") String datasetId, HubDatasetDataRequest request);

    @POST
    @Path("/api/hub/datasets/:datasetId/records/:recordId")
    HubDatasetOpResponse updateDatasetRecord(@PathParam("datasetId") String datasetId, @PathParam("recordId") String recordId,
                                             HubDatasetDataRequest request);

    @DELETE
    @Path("/api/hub/datasets/:datasetId/records/:recordId")
    HubDatasetOpResponse deleteDatasetRecord(@PathParam("datasetId") String datasetId, @PathParam("recordId") String recordId);

    @POST
    @Path("/api/hub/datasets/:datasetId/fields/rename")
    HubDatasetOpResponse renameDatasetField(@PathParam("datasetId") String datasetId, HubDatasetRenameRequest request);
}
