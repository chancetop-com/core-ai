package ai.core.server.systemprompt;

import ai.core.server.web.auth.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;

/**
 * @author Xander
 */
public class SystemPromptController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Inject
    SystemPromptService systemPromptService;

    @Inject
    WebContext webContext;

    public Response list(Request request) {
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "20"));
        var result = systemPromptService.list(offset, limit);
        return jsonResponse(result);
    }

    public Response create(Request request) {
        var userId = resolveUserId();
        var body = readBody(request, SystemPromptRequest.class);
        var result = systemPromptService.create(body, userId);
        return jsonResponse(result).status(core.framework.api.http.HTTPStatus.CREATED);
    }

    public Response get(Request request) {
        String promptId = request.pathParam("promptId");
        var result = systemPromptService.get(promptId);
        return jsonResponse(result);
    }

    public Response update(Request request) {
        var userId = resolveUserId();
        String promptId = request.pathParam("promptId");
        var body = readBody(request, SystemPromptRequest.class);
        var result = systemPromptService.update(promptId, body, userId);
        return jsonResponse(result);
    }

    public Response delete(Request request) {
        String promptId = request.pathParam("promptId");
        systemPromptService.delete(promptId);
        return Response.text("deleted");
    }

    public Response versions(Request request) {
        String promptId = request.pathParam("promptId");
        var result = systemPromptService.versions(promptId);
        return jsonResponse(result);
    }

    public Response getVersion(Request request) {
        String promptId = request.pathParam("promptId");
        int version = Integer.parseInt(request.pathParam("version"));
        var result = systemPromptService.getVersion(promptId, version);
        return jsonResponse(result);
    }

    public Response test(Request request) {
        String promptId = request.pathParam("promptId");
        var body = readBody(request, SystemPromptTestRequest.class);
        var result = systemPromptService.test(promptId, body);
        return jsonResponse(result);
    }

    private String resolveUserId() {
        var userId = AuthContext.userId(webContext);
        return userId != null ? userId : "anonymous";
    }

    private <T> T readBody(Request request, Class<T> type) {
        try {
            byte[] body = request.body().orElseThrow(() -> new IllegalArgumentException("empty body"));
            return MAPPER.readValue(body, type);
        } catch (Exception e) {
            throw new IllegalArgumentException("invalid request body: " + e.getMessage(), e);
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
