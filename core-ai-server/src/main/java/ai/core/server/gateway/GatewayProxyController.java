package ai.core.server.gateway;

import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.WebContext;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;

public class GatewayProxyController {
    @Inject
    GatewayProxyService gatewayProxyService;
    @Inject
    WebContext webContext;

    public Response chatCompletions(Request request) {
        return gatewayProxyService.proxyChatCompletions(body(request));
    }

    public Response responses(Request request) {
        return gatewayProxyService.proxyResponses(body(request));
    }

    public Response imageGenerations(Request request) {
        return gatewayProxyService.proxyImageGenerations(body(request));
    }

    public Response imageEdits(Request request) {
        return gatewayProxyService.proxyImageEdits(body(request));
    }

    public Response videoGenerations(Request request) {
        return gatewayProxyService.proxyVideoGenerations(body(request), currentOwner());
    }

    public Response videoStatus(Request request) {
        return gatewayProxyService.getVideoStatus(request.pathParam("id"), currentUserId());
    }

    public Response videoContent(Request request) {
        return gatewayProxyService.downloadVideoContent(request.pathParam("id"), currentUserId());
    }

    public Response models(Request request) {
        return gatewayProxyService.models();
    }

    private MediaJobOwner currentOwner() {
        return new MediaJobOwner(currentUserId(), null, null);
    }

    private String currentUserId() {
        return AuthContext.userId(webContext);
    }

    private byte[] body(Request request) {
        return request.body().orElseThrow(() -> new BadRequestException("body is required"));
    }
}
