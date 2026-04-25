package ai.core.cli.a2a.handler.chat;

import ai.core.agent.AgentPersistence;
import ai.core.cli.session.LocalChatSessionManager;
import ai.core.cli.session.LocalChatSessionManager.ChatSession;
import ai.core.session.FileSessionPersistence;
import ai.core.session.SessionPersistence;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Handler for /api/chat/sessions endpoint.
 * Returns list of local sessions from both memory and persisted storage.
 */
public class ChatSessionsHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatSessionsHandler.class);
    private static final ObjectMapper MAPPER = JsonUtil.OBJECT_MAPPER;
    private static final DateTimeFormatter ISO_FORMAT = DateTimeFormatter.ISO_INSTANT;
    private static final int TITLE_MAX_LENGTH = 60;

    private final LocalChatSessionManager sessionManager;
    private final FileSessionPersistence fileSessionPersistence;

    public ChatSessionsHandler(LocalChatSessionManager sessionManager, FileSessionPersistence fileSessionPersistence) {
        this.sessionManager = sessionManager;
        this.fileSessionPersistence = fileSessionPersistence;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        try {
            var params = exchange.getQueryParameters();
            int offset = 0;
            int limit = 50;

            var offsetParam = params.get("offset");
            if (offsetParam != null && !offsetParam.isEmpty()) {
                offset = Integer.parseInt(offsetParam.getFirst());
            }

            var limitParam = params.get("limit");
            if (limitParam != null && !limitParam.isEmpty()) {
                limit = Integer.parseInt(limitParam.getFirst());
            }

            // Get all sessions: persisted + in-memory
            var persistedSessions = sessionManager.listSessions();
            var activeSessions = sessionManager.getAllSessions();

            // Combine and deduplicate by session ID
            var allSessions = Stream.concat(
                       persistedSessions.stream().map(this::toPersistedSessionSummary),
                    activeSessions.stream().map(this::toActiveSessionSummary)
            ).collect(Collectors.toMap(
                    m -> (String) m.get("id"),
                    m -> m,
                    (existing, replacement) -> existing  // Keep existing if duplicate
            )).values().stream()
                    // Sort by last_message_at descending (newest first)
                    .sorted((a, b) -> {
                        var tsA = a.get("last_message_at");
                        var tsB = b.get("last_message_at");
                        if (tsA == null && tsB == null) return 0;
                        if (tsA == null) return 1;
                        if (tsB == null) return -1;
                        return ((String) tsB).compareTo((String) tsA);
                    })
                    .toList();

            var total = allSessions.size();
            var pagedSessions = allSessions.stream()
                    .skip(offset)
                    .limit(limit)
                    .collect(Collectors.toList());

            var response = new LinkedHashMap<String, Object>();
            response.put("sessions", pagedSessions);
            response.put("total", total);

            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.setStatusCode(200);
            exchange.getResponseSender().send(MAPPER.writeValueAsString(response));

        } catch (Exception e) {
            LOGGER.error("failed to list sessions", e);
            exchange.setStatusCode(500);
            exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
            exchange.getResponseSender().send("{\"error\":\"failed to list sessions\"}");
        }
    }

    private Map<String, Object> toPersistedSessionSummary(SessionPersistence.SessionInfo info) {
        var summary = new LinkedHashMap<String, Object>();
        summary.put("id", info.id());
        summary.put("user_id", null);
        summary.put("agent_id", "local");
        summary.put("source", "a2a");
        summary.put("schedule_id", null);
        summary.put("api_key_id", null);
        // Try to get title from first user message in persisted data
        String title = extractTitleFromPersistence(info.id());
        summary.put("title", title != null ? title : info.id());
        summary.put("message_count", null);
        summary.put("created_at", null);
        summary.put("last_message_at", info.lastModified() != null
            ? ISO_FORMAT.format(info.lastModified().atZone(ZoneId.of("UTC")))
            : null);
        return summary;
    }

    private String extractTitleFromPersistence(String sessionId) {
        try {
            var data = fileSessionPersistence.load(sessionId).orElse(null);
            if (data == null) return null;
            String firstMessage = AgentPersistence.firstUserMessage(data);
            if (firstMessage != null && !firstMessage.isBlank()) {
                return truncateTitle(firstMessage);
            }
        } catch (Exception e) {
            LOGGER.debug("failed to extract title from session data, sessionId={}", sessionId, e);
        }
        return null;
    }

    private String truncateTitle(String text) {
        if (text == null) return null;
        text = text.trim().replaceAll("\\s+", " ");
        if (text.length() <= TITLE_MAX_LENGTH) return text;
        return text.substring(0, TITLE_MAX_LENGTH - 3) + "...";
    }

    private Map<String, Object> toActiveSessionSummary(ChatSession chatSession) {
        var session = chatSession.session;
        var info = new LinkedHashMap<String, Object>();
        info.put("id", session.id());
        info.put("user_id", null);
        info.put("agent_id", "local");
        info.put("source", "a2a");
        info.put("schedule_id", null);
        info.put("api_key_id", null);
        info.put("title", "Active Session");
        info.put("message_count", null);
        info.put("created_at", null);
        info.put("last_message_at", null);
        return info;
    }
}
