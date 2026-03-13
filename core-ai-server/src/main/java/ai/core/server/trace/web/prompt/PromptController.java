package ai.core.server.trace.web.prompt;

import com.fasterxml.jackson.databind.ObjectMapper;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import ai.core.server.trace.domain.PromptTemplate;
import ai.core.server.trace.service.PromptService;

/**
 * @author Xander
 */
public class PromptController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Inject
    PromptService promptService;

    public Response list(Request request) {
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        var prompts = promptService.list(offset, limit);
        return jsonResponse(prompts);
    }

    public Response create(Request request) {
        var body = readBody(request);
        var created = promptService.create(body);
        return jsonResponse(created).status(core.framework.api.http.HTTPStatus.CREATED);
    }

    public Response get(Request request) {
        String promptId = request.pathParam("promptId");
        var prompt = promptService.get(promptId);
        if (prompt == null) {
            return Response.text("not found").status(core.framework.api.http.HTTPStatus.NOT_FOUND);
        }
        return jsonResponse(prompt);
    }

    public Response update(Request request) {
        String promptId = request.pathParam("promptId");
        var body = readBody(request);
        var updated = promptService.update(promptId, body);
        return jsonResponse(updated);
    }

    public Response delete(Request request) {
        String promptId = request.pathParam("promptId");
        promptService.delete(promptId);
        return Response.text("deleted");
    }

    public Response publish(Request request) {
        String promptId = request.pathParam("promptId");
        var published = promptService.publish(promptId);
        return jsonResponse(published);
    }

    private PromptTemplate readBody(Request request) {
        try {
            byte[] body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            return MAPPER.readValue(body, PromptTemplate.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid request body: " + e.getMessage());
        }
    }

    private Response jsonResponse(Object data) {
        try {
            var json = MAPPER.writeValueAsBytes(data);
            return Response.bytes(json).contentType(core.framework.http.ContentType.APPLICATION_JSON);
        } catch (Exception e) {
            return Response.text("serialization error").status(core.framework.api.http.HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
