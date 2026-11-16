package ai.core.server.messaging;

import ai.core.utils.JsonUtil;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.web.Request;
import core.framework.web.Response;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * Pod-to-pod RPC endpoint. Authenticated by a shared token and addressed by {@code X-Target-Pod}
 * so a recycled pod IP cannot silently receive another pod's command.
 *
 * @author stephen
 */
public class InternalRpcController {
    private static final Logger LOGGER = LoggerFactory.getLogger(InternalRpcController.class);
    private static final ContentType JSON = ContentType.APPLICATION_JSON;

    private final InProcessCommandHandler commandHandler;
    private final PodRegistry podRegistry;
    private final String token;
    private final String previousToken;

    public InternalRpcController(InProcessCommandHandler commandHandler, PodRegistry podRegistry, String token, String previousToken) {
        this.commandHandler = commandHandler;
        this.podRegistry = podRegistry;
        this.token = token;
        this.previousToken = previousToken;
    }

    public Response handle(Request request) {
        if (!authenticated(request)) {
            return json(HTTPStatus.UNAUTHORIZED, RpcClient.errorResponse("unauthorized"));
        }
        var targetPod = request.header("X-Target-Pod").orElse("");
        if (targetPod.isBlank()) {
            return json(HTTPStatus.BAD_REQUEST, RpcClient.errorResponse("X-Target-Pod is required"));
        }
        if (!podRegistry.podId().equals(targetPod)) {
            return json(HTTPStatus.CONFLICT, RpcClient.errorResponse("target pod mismatch, expected " + podRegistry.podId()));
        }
        var body = request.body().orElse(null);
        if (body == null || body.length == 0) {
            return json(HTTPStatus.BAD_REQUEST, RpcClient.errorResponse("request body is required"));
        }

        InternalRpcCommand message;
        try {
            message = JsonUtil.fromJson(InternalRpcCommand.class, new String(body, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            return json(HTTPStatus.BAD_REQUEST, RpcClient.errorResponse("invalid request body"));
        }
        var validation = validate(message);
        if (validation != null) {
            return json(HTTPStatus.BAD_REQUEST, RpcClient.errorResponse(validation));
        }

        try {
            var command = new SessionCommand(CommandType.valueOf(message.type), message.sessionId,
                message.userId, message.payload, message.requestId);
            return json(HTTPStatus.OK, commandHandler.handleRpc(command));
        } catch (Throwable t) {
            LOGGER.error("internal rpc failed, type={}, sessionId={}", message.type, message.sessionId, t);
            return json(HTTPStatus.INTERNAL_SERVER_ERROR, RpcClient.errorResponse(t.getMessage()));
        }
    }

    private String validate(InternalRpcCommand message) {
        if (message == null) return "request body is required";
        if (message.type == null || message.type.isBlank()) return "type is required";
        if (message.sessionId == null || message.sessionId.isBlank()) return "sessionId is required";
        if (message.payload == null) return "payload is required";
        if (message.requestId == null || message.requestId.isBlank()) return "requestId is required";
        try {
            if (!CommandType.valueOf(message.type).rpc()) return "not an rpc command: " + message.type;
        } catch (IllegalArgumentException e) {
            return "unknown command type: " + message.type;
        }
        return null;
    }

    private boolean authenticated(Request request) {
        var provided = request.header("X-Internal-Token").orElse("");
        return matches(provided, token) || matchesPrevious(provided);
    }

    private boolean matchesPrevious(String provided) {
        return previousToken != null && !previousToken.isBlank() && matches(provided, previousToken);
    }

    private boolean matches(String provided, String expected) {
        if (expected == null || expected.isEmpty() || provided.isEmpty()) return false;
        return MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8));
    }

    private Response json(HTTPStatus status, String body) {
        return Response.bytes(body.getBytes(StandardCharsets.UTF_8))
            .contentType(JSON)
            .status(status);
    }
}

