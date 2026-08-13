package ai.core.api.server.prompt;

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
public interface PromptWebService {
    @GET
    @Path("/api/prompts")
    ListPromptsResponse list(ListPromptsRequest request);

    @POST
    @Path("/api/prompts")
    @ResponseStatus(HTTPStatus.CREATED)
    PromptTemplateView create(PromptTemplateView request);

    @GET
    @Path("/api/prompts/:promptId")
    PromptTemplateView get(@PathParam("promptId") String promptId);

    @PUT
    @Path("/api/prompts/:promptId")
    PromptTemplateView update(@PathParam("promptId") String promptId, PromptTemplateView request);

    @DELETE
    @Path("/api/prompts/:promptId")
    void delete(@PathParam("promptId") String promptId);

    @POST
    @Path("/api/prompts/:promptId/publish")
    PromptTemplateView publish(@PathParam("promptId") String promptId);
}
