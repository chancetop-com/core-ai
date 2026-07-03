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

import java.util.HashSet;

public class GatewayModelController {
    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();

    @Inject
    GatewayModelService gatewayModelService;
    @Inject
    GatewayModelDiscoveryService gatewayModelDiscoveryService;
    @Inject
    WebContext webContext;

    public Response list(Request request) {
        return jsonResponse(gatewayModelService.list(userId()));
    }

    public Response create(Request request) {
        var body = readBody(request);
        return jsonResponse(gatewayModelService.create(body, userId())).status(HTTPStatus.CREATED);
    }

    public Response update(Request request) {
        var body = readBody(request);
        return jsonResponse(gatewayModelService.update(request.pathParam("id"), body, userId()));
    }

    public Response delete(Request request) {
        gatewayModelService.delete(request.pathParam("id"), userId());
        return Response.empty().status(HTTPStatus.NO_CONTENT);
    }

    public Response discover(Request request) {
        return jsonResponse(gatewayModelDiscoveryService.discover(request.pathParam("id"), userId()));
    }

    public Response importModels(Request request) {
        var body = readBody(request, ImportGatewayModelsRequest.class);
        return jsonResponse(gatewayModelService.importModels(request.pathParam("id"), body, userId()));
    }

    private String userId() {
        return AuthContext.userId(webContext);
    }

    private GatewayModelRequest readBody(Request request) {
        try {
            byte[] body = request.body().orElseThrow(() -> new BadRequestException("body is required"));
            var node = MAPPER.readTree(body);
            if (!node.isObject()) throw new BadRequestException("request body must be an object");
            var value = MAPPER.treeToValue(node, GatewayModelRequest.class);
            var fields = new HashSet<String>();
            node.fieldNames().forEachRemaining(fields::add);
            value.fields = fields;
            return value;
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("invalid request body: " + e.getMessage());
        }
    }

    private <T> T readBody(Request request, Class<T> type) {
        try {
            byte[] body = request.body().orElseThrow(() -> new BadRequestException("body is required"));
            return MAPPER.readValue(body, type);
        } catch (BadRequestException e) {
            throw e;
        } catch (Exception e) {
            throw new BadRequestException("invalid request body: " + e.getMessage());
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
