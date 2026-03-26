package ai.core.cli.a2a.handler;

import ai.core.agent.AgentPersistence;
import ai.core.a2a.A2ARunManager;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionPersistence;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * @author stephen
 */
public class SessionsHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(SessionsHandler.class);
    private static final String SESSIONS_DIR = Path.of(System.getProperty("user.home"), ".core-ai", "sessions").toString();
    private static final DateTimeFormatter DISPLAY_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

    private final A2ARunManager runManager;
    private final FileSessionPersistence sessionPersistence;

    public SessionsHandler(A2ARunManager runManager) {
        this.runManager = runManager;
        this.sessionPersistence = new FileSessionPersistence(SESSIONS_DIR);
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        try {
            List<SessionPersistence.SessionInfo> sessions = runManager.listSessions(sessionPersistence);
            String currentSessionId = runManager.getSessionId();

            var result = sessions.stream()
                    .limit(20)
                    .map(info -> {
                        String timeStr = java.time.LocalDateTime.ofInstant(info.lastModified(), ZoneId.systemDefault())
                                .format(DISPLAY_FORMAT);
                        String firstMessage = getFirstUserMessage(info.id());
                        return new SessionItem(info.id(), timeStr, firstMessage, info.id().equals(currentSessionId));
                    })
                    .toList();

            sendJson(exchange, JsonUtil.toJson(result));
        } catch (Exception e) {
            LOGGER.error("failed to list sessions", e);
            exchange.setStatusCode(500);
            sendJson(exchange, "{\"error\":\"internal server error\"}");
        }
    }

    private String getFirstUserMessage(String sessionId) {
        try {
            return sessionPersistence.load(sessionId)
                    .map(data -> {
                        String text = AgentPersistence.firstUserMessage(data);
                        if (text == null || text.isBlank()) return "(empty)";
                        text = text.replaceAll("[\\r\\n]+", " ").strip();
                        return text.length() > 60 ? text.substring(0, 60) + "..." : text;
                    })
                    .orElse("(not found)");
        } catch (Exception e) {
            return "(error)";
        }
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }

    public static class SessionItem {
        public String id;
        public String time;
        public String firstMessage;
        public boolean isCurrent;

        public SessionItem(String id, String time, String firstMessage, boolean isCurrent) {
            this.id = id;
            this.time = time;
            this.firstMessage = firstMessage;
            this.isCurrent = isCurrent;
        }
    }
}
