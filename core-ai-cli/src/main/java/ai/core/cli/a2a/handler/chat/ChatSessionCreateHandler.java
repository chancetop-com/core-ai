package ai.core.cli.a2a.handler.chat;

import ai.core.cli.session.LocalChatSessionManager;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * @author stephen
 */
public class ChatSessionCreateHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatSessionCreateHandler.class);

    private final LocalChatSessionManager sessionManager;

    public ChatSessionCreateHandler(LocalChatSessionManager sessionManager) {
        this.sessionManager = sessionManager;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        try {
            String body = readBody(exchange);
            String sessionId = null;

            if (body != null && !body.isEmpty()) {
                var request = JsonUtil.fromJson(CreateSessionRequest.class, body);
                // For local mode, agent_id is ignored - we always use the local agent
                sessionId = request.agentId != null ? request.agentId : null;
            }

            String id = sessionManager.createSession(sessionId);
            sendJson(exchange, JsonUtil.toJson(new CreateSessionResponse(id)));
        } catch (Exception e) {
            LOGGER.error("failed to create session", e);
            exchange.setStatusCode(500);
            sendJson(exchange, "{\"error\":\"internal server error\"}");
        }
    }

    private String readBody(HttpServerExchange exchange) throws IOException {
        exchange.startBlocking();
        var stream = exchange.getInputStream();
        if (stream == null) return null;
        byte[] bytes = stream.readAllBytes();
        if (bytes.length == 0) return null;
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.setStatusCode(201);
        exchange.getResponseSender().send(json);
    }

    public static class CreateSessionRequest {
        public String agentId;
    }

    public static class CreateSessionResponse {
        public String sessionId;

        public CreateSessionResponse(String sessionId) {
            this.sessionId = sessionId;
        }
    }
}
