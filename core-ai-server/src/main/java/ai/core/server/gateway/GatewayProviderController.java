package ai.core.server.gateway;

import ai.core.server.web.auth.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;

public class GatewayProviderController {
    private static final ObjectMapper MAPPER = GatewayJson.MAPPER;

    @Inject
    GatewayProviderService gatewayProviderService;
    @Inject
    WebContext webContext;

    public Response list(Request request) {
        return jsonResponse(gatewayProviderService.list(userId()));
    }

    public Response create(Request request) {
        var body = readBody(request);
        return jsonResponse(gatewayProviderService.create(body, userId())).status(HTTPStatus.CREATED);
    }

    public Response update(Request request) {
        var body = readBody(request);
        return jsonResponse(gatewayProviderService.update(request.pathParam("id"), body, userId()));
    }

    public Response delete(Request request) {
        var cascadeModels = "true".equalsIgnoreCase(request.queryParams().get("cascade"));
        gatewayProviderService.delete(request.pathParam("id"), userId(), cascadeModels);
        return Response.empty().status(HTTPStatus.NO_CONTENT);
    }

    public Response test(Request request) {
        return jsonResponse(gatewayProviderService.test(request.pathParam("id"), userId()));
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }

    private GatewayProviderRequest readBody(Request request) {
        try {
            byte[] body = request.body().orElseThrow(() -> new BadRequestException("body is required"));
            return MAPPER.readValue(body, GatewayProviderRequest.class);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("invalid request body: " + e.getMessage(), "BAD_REQUEST", e);
        }
    }

    private Response jsonResponse(Object data) {
        try {
            return Response.bytes(MAPPER.writeValueAsBytes(data)).contentType(ContentType.APPLICATION_JSON);
        } catch (Exception e) {
            return Response.text("serialization error").status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }
}
