package ai.core.api.server;

import ai.core.api.server.dataset.CreateDatasetRequest;
import ai.core.api.server.dataset.DatasetView;
import ai.core.api.server.dataset.ListDatasetRecordsResponse;
import ai.core.api.server.dataset.ListDatasetsRequest;
import ai.core.api.server.dataset.ListDatasetsResponse;
import ai.core.api.server.dataset.UpdateDatasetRequest;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.DELETE;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.PUT;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * @author stephen
 */
public interface DatasetWebService {
    @POST
    @Path("/api/datasets")
    @ResponseStatus(HTTPStatus.CREATED)
    DatasetView create(CreateDatasetRequest request);

    @GET
    @Path("/api/datasets")
    ListDatasetsResponse list(ListDatasetsRequest request);

    @GET
    @Path("/api/datasets/:id")
    DatasetView get(@PathParam("id") String id);

    @PUT
    @Path("/api/datasets/:id")
    DatasetView update(@PathParam("id") String id, UpdateDatasetRequest request);

    @DELETE
    @Path("/api/datasets/:id")
    void delete(@PathParam("id") String id);

    @GET
    @Path("/api/datasets/:id/records")
    ListDatasetRecordsResponse listRecords(@PathParam("id") String id);
}
