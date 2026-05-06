package ai.core.server.a2a;

import ai.core.api.a2a.SendMessageRequest;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

import java.nio.charset.StandardCharsets;

/**
 * @author xander
 */
public class A2AMessageController {
    private static final ContentType A2A_JSON = ContentType.parse("application/a2a+json");

    @Inject
    WebContext webContext;
    @Inject
    ServerA2AService a2aService;

    public Response send(Request request) {
        var messageRequest = parse(request);
        var agentId = A2AAgentIdResolver.resolve(request, messageRequest);
        var result = a2aService.send(agentId, messageRequest, AuthContext.userId(webContext));
        return json(JsonUtil.toJson(result.response));
    }

    private SendMessageRequest parse(Request request) {
        var body = request.body().orElseThrow(() -> new BadRequestException("request body required"));
        return JsonUtil.fromJson(SendMessageRequest.class, new String(body, StandardCharsets.UTF_8));
    }

    private Response json(String body) {
        return Response.bytes(body.getBytes(StandardCharsets.UTF_8)).contentType(A2A_JSON);
    }
}
