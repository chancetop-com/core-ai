package ai.core.server.trace.web.prompt;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import ai.core.server.trace.domain.PromptTemplate;
import ai.core.server.trace.service.PromptService;

/**
 * @author Xander
 */
public class PromptController {
    @Inject
    PromptService promptService;

    public Response list(Request request) {
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        var prompts = promptService.list(offset, limit);
        return Response.bean(prompts);
    }

    public Response create(Request request) {
        var template = request.bean(PromptTemplate.class);
        var created = promptService.create(template);
        return Response.bean(created).status(core.framework.api.http.HTTPStatus.CREATED);
    }

    public Response get(Request request) {
        String promptId = request.pathParam("promptId");
        var prompt = promptService.get(promptId);
        if (prompt == null) {
            return Response.text("not found").status(core.framework.api.http.HTTPStatus.NOT_FOUND);
        }
        return Response.bean(prompt);
    }

    public Response update(Request request) {
        String promptId = request.pathParam("promptId");
        var template = request.bean(PromptTemplate.class);
        var updated = promptService.update(promptId, template);
        return Response.bean(updated);
    }

    public Response delete(Request request) {
        String promptId = request.pathParam("promptId");
        promptService.delete(promptId);
        return Response.text("deleted").status(core.framework.api.http.HTTPStatus.OK);
    }

    public Response publish(Request request) {
        String promptId = request.pathParam("promptId");
        var published = promptService.publish(promptId);
        return Response.bean(published);
    }
}
