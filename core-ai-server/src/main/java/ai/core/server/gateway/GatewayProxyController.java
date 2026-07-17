package ai.core.server.gateway;

import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.exception.BadRequestException;

public class GatewayProxyController {
    @Inject
    GatewayProxyService gatewayProxyService;

    public Response chatCompletions(Request request) {
        return gatewayProxyService.proxyChatCompletions(body(request));
    }

    public Response responses(Request request) {
        return gatewayProxyService.proxyResponses(body(request));
    }

    public Response imageGenerations(Request request) {
        return gatewayProxyService.proxyImageGenerations(body(request));
    }

    public Response videoGenerations(Request request) {
        return gatewayProxyService.proxyVideoGenerations(body(request));
    }

    public Response videoStatus(Request request) {
        return gatewayProxyService.getVideoStatus(request.pathParam("id"));
    }

    public Response videoContent(Request request) {
        return gatewayProxyService.downloadVideoContent(request.pathParam("id"));
    }

    public Response models(Request request) {
        return gatewayProxyService.models();
    }

    private byte[] body(Request request) {
        return request.body().orElseThrow(() -> new BadRequestException("body is required"));
    }
}
