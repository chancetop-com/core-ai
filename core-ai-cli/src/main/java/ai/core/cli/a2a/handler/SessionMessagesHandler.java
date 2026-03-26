package ai.core.cli.a2a.handler;

import ai.core.agent.AgentPersistence;
import ai.core.llm.domain.Message;
import ai.core.llm.domain.RoleType;
import ai.core.session.FileSessionPersistence;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.List;

/**
 * @author stephen
 */
public class SessionMessagesHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionMessagesHandler.class);
    private static final String SESSIONS_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "sessions").toString();

    private final FileSessionPersistence sessionPersistence;

    public SessionMessagesHandler() {
        this.sessionPersistence = new FileSessionPersistence(SESSIONS_DIR);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        String path = exchange.getRequestPath();
        String sessionId = extractSessionId(path);
        if (sessionId == null) {
            exchange.setStatusCode(400);
            sendJson(exchange, "{\"error\":\"missing session id\"}");
            return;
        }

        try {
            List<Message> messages = sessionPersistence.load(sessionId)
                    .map(data -> {
                        var domain = JsonUtil.fromJson(AgentPersistence.AgentPersistenceDomain.class, data);
                        return domain.messages;
                    })
                    .orElse(null);

            if (messages == null) {
                sendJson(exchange, "{\"error\":\"session not found\"}");
                return;
            }

            var result = messages.stream()
                    .map(msg -> {
                        String text = msg.getTextContent();
                        String role = msg.role == RoleType.USER ? "user" : "agent";
                        return new MessageDto(role, text != null ? text : "");
                    })
                    .toList();

            sendJson(exchange, JsonUtil.toJson(result));
        } catch (Exception e) {
            LOGGER.error("failed to load session messages", e);
            exchange.setStatusCode(500);
            sendJson(exchange, "{\"error\":\"internal server error\"}");
        }
    }

    private String extractSessionId(String path) {
        String prefix = "/api/sessions/";
        if (!path.startsWith(prefix)) return null;
        String remainder = path.substring(prefix.length());
        int slashIndex = remainder.indexOf('/');
        return slashIndex > 0 ? remainder.substring(0, slashIndex) : remainder;
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }

    public static class MessageDto {
        public String role;
        public String content;

        public MessageDto(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
