package ai.core.api.server.apiuser;

import ai.core.api.server.apiuser.request.CreateApiUserRequest;
import ai.core.api.server.apiuser.request.UpdateStatusRequest;
import ai.core.api.server.apiuser.response.CreateApiUserResponse;
import ai.core.api.server.apiuser.response.ListApiUsersResponse;
import core.framework.api.http.HTTPStatus;
import core.framework.api.web.service.GET;
import core.framework.api.web.service.POST;
import core.framework.api.web.service.Path;
import core.framework.api.web.service.PathParam;
import core.framework.api.web.service.ResponseStatus;

/**
 * Admin surface for API user management (manager-type users representing business systems).
 * Only reachable by core-ai admin accounts.
 *
 * @author core-ai
 */
public interface AdminApiUserWebService {
    @POST
    @Path("/api/admin/api-users")
    @ResponseStatus(HTTPStatus.OK)
    CreateApiUserResponse create(CreateApiUserRequest request);

    @GET
    @Path("/api/admin/api-users")
    ListApiUsersResponse list();

    @POST
    @Path("/api/admin/api-users/:id/rotate-key")
    @ResponseStatus(HTTPStatus.OK)
    CreateApiUserResponse rotateKey(@PathParam("id") String id);

    @POST
    @Path("/api/admin/api-users/:id/update-status")
    @ResponseStatus(HTTPStatus.OK)
    void updateStatus(@PathParam("id") String id, UpdateStatusRequest request);
}
