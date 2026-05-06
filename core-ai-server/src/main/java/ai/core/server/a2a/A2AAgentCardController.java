package ai.core.server.a2a;

import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;

import java.nio.charset.StandardCharsets;

/**
 * @author xander
 */
public class A2AAgentCardController {
    private static final ContentType A2A_JSON = ContentType.parse("application/a2a+json");

    @Inject
    ServerA2AService a2aService;

    public Response get(Request request) {
        var card = a2aService.agentCard(A2AAgentIdResolver.resolve(request));
        return json(JsonUtil.toJson(card));
    }

    private Response json(String body) {
        return Response.bytes(body.getBytes(StandardCharsets.UTF_8)).contentType(A2A_JSON);
    }
}
