package ai.core.server.gateway;

import ai.core.server.rbac.PermissionsBypass;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;

@PermissionsBypass
public class GatewayProxyController {
    @Inject
    GatewayProxyService gatewayProxyService;
    @Inject
    WebContext webContext;

    public Response chatCompletions(Request request) {
        var requestBody = body(request);
        var userId = currentUserId();
        return gatewayProxyService.proxyChatCompletions(requestBody, userId, GatewaySupport.sessionId(request, userId, requestBody), GatewaySupport.agentName(request));
    }

    public Response responses(Request request) {
        var requestBody = body(request);
        var userId = currentUserId();
        return gatewayProxyService.proxyResponses(requestBody, userId, GatewaySupport.sessionId(request, userId, requestBody), GatewaySupport.agentName(request));
    }

    public Response models(Request request) {
        return gatewayProxyService.models();
    }

    private String currentUserId() {
        return AuthContext.userId(webContext);
    }

    private byte[] body(Request request) {
        return request.body().orElseThrow(() -> new BadRequestException("body is required"));
    }
}
