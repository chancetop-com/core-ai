package ai.core.api.server.systemprompt;

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
public interface SystemPromptWebService {
    @GET
    @Path("/api/system-prompts")
    ListSystemPromptsResponse list(ListSystemPromptsRequest request);

    @POST
    @Path("/api/system-prompts")
    @ResponseStatus(HTTPStatus.CREATED)
    SystemPromptView create(SystemPromptRequest request);

    @GET
    @Path("/api/system-prompts/:promptId")
    SystemPromptView get(@PathParam("promptId") String promptId);

    @PUT
    @Path("/api/system-prompts/:promptId")
    SystemPromptView update(@PathParam("promptId") String promptId, SystemPromptRequest request);

    @DELETE
    @Path("/api/system-prompts/:promptId")
    void delete(@PathParam("promptId") String promptId);

    @GET
    @Path("/api/system-prompts/:promptId/versions")
    ListSystemPromptVersionsResponse versions(@PathParam("promptId") String promptId);

    @GET
    @Path("/api/system-prompts/:promptId/versions/:version")
    SystemPromptView getVersion(@PathParam("promptId") String promptId, @PathParam("version") Integer version);

    @POST
    @Path("/api/system-prompts/:promptId/test")
    SystemPromptTestResponse test(@PathParam("promptId") String promptId, SystemPromptTestRequest request);
}
