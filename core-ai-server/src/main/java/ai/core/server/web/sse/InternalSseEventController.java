package ai.core.server.web.sse;

import ai.core.server.messaging.InternalSseEvent;
import ai.core.server.messaging.PodRegistry;
import ai.core.server.messaging.RpcClient;
import ai.core.server.messaging.SessionOwnershipRegistry;
import ai.core.server.messaging.SseEventCodec;
import ai.core.server.web.InternalTokenAuth;
import ai.core.utils.JsonUtil;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.web.Request;
import core.framework.web.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Low-frequency forwarding path for events produced on a pod that is not the session owner.
 * It is a regular HTTP route, not an SSE route; chunks never use it.
 *
 * @author stephen
 */
public class InternalSseEventController {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalSseEventController.class);
    private static final ContentType JSON = ContentType.APPLICATION_JSON;

    private final PodRegistry podRegistry;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final SessionEventHubRegistry hubRegistry;
    private final String token;
    private final String previousToken;

    public InternalSseEventController(PodRegistry podRegistry, SessionOwnershipRegistry ownershipRegistry,
                                      SessionEventHubRegistry hubRegistry, String token, String previousToken) {
        this.podRegistry = podRegistry;
        this.ownershipRegistry = ownershipRegistry;
        this.hubRegistry = hubRegistry;
        this.token = token;
        this.previousToken = previousToken;
    }

    public Response handle(Request request) {
        if (!podRegistry.host().equals(request.hostname())) {
            return json(HTTPStatus.NOT_FOUND, RpcClient.errorResponse("not found"));
        }
        if (!InternalTokenAuth.matches(request.header("X-Internal-Token").orElse(""), token, previousToken)) {
            return json(HTTPStatus.UNAUTHORIZED, RpcClient.errorResponse("unauthorized"));
        }
        var body = request.body().orElse(null);
        if (body == null || body.length == 0) {
            return json(HTTPStatus.BAD_REQUEST, RpcClient.errorResponse("request body is required"));
        }
        InternalSseEvent message;
        try {
            message = JsonUtil.fromJson(InternalSseEvent.class, new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return json(HTTPStatus.BAD_REQUEST, RpcClient.errorResponse("invalid request body"));
        }
        if (message == null || message.sessionId == null || message.sessionId.isBlank()
                || message.eventJson == null || message.eventJson.isBlank()) {
            return json(HTTPStatus.BAD_REQUEST, RpcClient.errorResponse("sessionId and eventJson are required"));
        }
        if (!ownershipRegistry.isOwner(message.sessionId)) {
            return json(HTTPStatus.CONFLICT, RpcClient.errorResponse("not the owner of " + message.sessionId));
        }
        var event = SseEventCodec.fromJson(message.eventJson);
        if (event == null) {
            return json(HTTPStatus.BAD_REQUEST, RpcClient.errorResponse("unsupported event"));
        }
        try {
            hubRegistry.getOrCreate(message.sessionId).publish(event);
            return json(HTTPStatus.OK, "{\"ok\":true}");
        } catch (RuntimeException e) {
            LOGGER.warn("failed to publish forwarded session event, sessionId={}", message.sessionId, e);
            return json(HTTPStatus.INTERNAL_SERVER_ERROR, RpcClient.errorResponse(e.getMessage()));
        }
    }

    private Response json(HTTPStatus status, String body) {
        return Response.bytes(body.getBytes(StandardCharsets.UTF_8))
                .contentType(JSON)
                .status(status);
    }
}
