package ai.core.cli.a2a.handler.chat;

import ai.core.agent.AgentPersistence;
import ai.core.api.server.session.ApproveToolCallRequest;
import ai.core.api.server.session.SendMessageRequest;
import ai.core.cli.session.LocalChatSessionManager;
import ai.core.llm.domain.RoleType;
import ai.core.session.FileSessionPersistence;
import ai.core.cli.utils.PathUtils;
import ai.core.utils.JsonUtil;
import io.undertow.server.HttpHandler;
import io.undertow.server.HttpServerExchange;
import io.undertow.util.Headers;
import io.undertow.util.Methods;
import io.undertow.util.PathTemplateMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public class ChatSessionActionHandler implements HttpHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChatSessionActionHandler.class);

    private final LocalChatSessionManager sessionManager;
    private final FileSessionPersistence filePersistence;

    public ChatSessionActionHandler(LocalChatSessionManager sessionManager, FileSessionPersistence filePersistence) {
        this.sessionManager = sessionManager;
        this.filePersistence = filePersistence;
    }

    @Override
    public void handleRequest(HttpServerExchange exchange) {
        if (exchange.isInIoThread()) {
            exchange.dispatch(this);
            return;
        }

        var match = exchange.getAttachment(PathTemplateMatch.ATTACHMENT_KEY);
        String sessionId = match != null ? match.getParameters().get("sessionId") : null;
        if (sessionId == null) {
            exchange.setStatusCode(400);
            sendJson(exchange, "{\"error\":\"missing sessionId\"}");
            return;
        }

        var method = exchange.getRequestMethod();
        var path = exchange.getRelativePath();

        try {
            if (path.endsWith("/history") && Methods.GET.equals(method)) {
                handleHistory(exchange, sessionId);
            } else if (path.endsWith("/messages") && Methods.GET.equals(method)) {
                handleGetMessages(exchange, sessionId);
            } else if (path.endsWith("/messages") && Methods.POST.equals(method)) {
                handleSendMessage(exchange, sessionId);
            } else if (path.endsWith("/approve") && Methods.POST.equals(method)) {
                handleApprove(exchange, sessionId);
            } else if (path.endsWith("/cancel") && Methods.POST.equals(method)) {
                handleCancel(exchange, sessionId);
            } else if (path.endsWith("/tools") && Methods.POST.equals(method)) {
                handleLoadTools(exchange, sessionId);
            } else if (path.endsWith("/skills") && Methods.POST.equals(method)) {
                handleLoadSkills(exchange, sessionId);
            } else if (Methods.GET.equals(method) && !path.contains("/")) {
                handleGetInfo(exchange, sessionId);
            } else if (Methods.DELETE.equals(method)) {
                handleClose(exchange, sessionId);
            } else {
                exchange.setStatusCode(405);
                exchange.getResponseSender().send("Method not allowed");
            }
        } catch (Exception e) {
            LOGGER.error("failed to handle session action", e);
            exchange.setStatusCode(500);
            sendJson(exchange, "{\"error\":\"internal server error\"}");
        }
    }

    private void handleGetInfo(HttpServerExchange exchange, String sessionId) {
        var chatSession = sessionManager.getSession(sessionId);
        var info = new SessionInfo();
        info.id = sessionId;
        info.agentId = "local";

        if (chatSession != null) {
            info.loadedToolIds = new ArrayList<>();
            info.loadedSkillIds = new ArrayList<>();
        }

        sendJson(exchange, JsonUtil.toJson(info));
    }

    private void handleHistory(HttpServerExchange exchange, String sessionId) {
        Optional<String> data = filePersistence.load(sessionId);
        if (data.isEmpty()) {
            sendJson(exchange, "{\"messages\":[]}");
            return;
        }

        try {
            var domain = JsonUtil.fromJson(AgentPersistence.AgentPersistenceDomain.class, data.get());
            var messages = new ArrayList<HistoryMessage>();
            if (domain.messages != null) {
                for (var msg : domain.messages) {
                    // Skip system messages
                    if (msg.role == RoleType.SYSTEM) continue;
                    String text = msg.getTextContent();
                    String role = msg.role == RoleType.USER ? "user" : "agent";
                    messages.add(new HistoryMessage(role, text != null ? text : ""));
                }
            }
            sendJson(exchange, JsonUtil.toJson(new HistoryResponse(messages)));
        } catch (Exception e) {
            LOGGER.error("failed to parse session history", e);
            sendJson(exchange, "{\"messages\":[]}");
        }
    }

    private void handleGetMessages(HttpServerExchange exchange, String sessionId) {
        Optional<String> data = filePersistence.load(sessionId);
        if (data.isEmpty()) {
            sendJson(exchange, "[]");
            return;
        }

        try {
            var domain = JsonUtil.fromJson(AgentPersistence.AgentPersistenceDomain.class, data.get());
            var messages = new ArrayList<SimpleMessage>();
            if (domain.messages != null) {
                for (var msg : domain.messages) {
                    // Skip system messages
                    if (msg.role == RoleType.SYSTEM) continue;
                    String text = msg.getTextContent();
                    String role = msg.role == RoleType.USER ? "user" : "agent";
                    messages.add(new SimpleMessage(role, text != null ? text : ""));
                }
            }
            sendJson(exchange, JsonUtil.toJson(messages));
        } catch (Exception e) {
            LOGGER.error("failed to parse session messages", e);
            sendJson(exchange, "[]");
        }
    }

    private void handleSendMessage(HttpServerExchange exchange, String sessionId) {
        String body;
        try {
            body = readBody(exchange);
        } catch (IOException e) {
            LOGGER.error("failed to read request body", e);
            exchange.setStatusCode(500);
            sendJson(exchange, "{\"error\":\"internal server error\"}");
            return;
        }
        if (body == null || body.isEmpty()) {
            exchange.setStatusCode(400);
            sendJson(exchange, "{\"error\":\"request body required\"}");
            return;
        }

        var request = JsonUtil.fromJson(SendMessageRequest.class, body);
        if (request.message == null || request.message.isEmpty()) {
            exchange.setStatusCode(400);
            sendJson(exchange, "{\"error\":\"message required\"}");
            return;
        }

        var chatSession = sessionManager.getSession(sessionId);
        if (chatSession == null) {
            exchange.setStatusCode(404);
            sendJson(exchange, "{\"error\":\"session not found\"}");
            return;
        }

        chatSession.session.sendMessage(request.message);
        sendJson(exchange, "{}");
    }

    private void handleApprove(HttpServerExchange exchange, String sessionId) {
        String body;
        try {
            body = readBody(exchange);
        } catch (IOException e) {
            LOGGER.error("failed to read request body", e);
            exchange.setStatusCode(500);
            sendJson(exchange, "{\"error\":\"internal server error\"}");
            return;
        }
        if (body == null || body.isEmpty()) {
            exchange.setStatusCode(400);
            sendJson(exchange, "{\"error\":\"request body required\"}");
            return;
        }

        var request = JsonUtil.fromJson(ApproveToolCallRequest.class, body);
        var chatSession = sessionManager.getSession(sessionId);
        if (chatSession == null) {
            exchange.setStatusCode(404);
            sendJson(exchange, "{\"error\":\"session not found\"}");
            return;
        }

        chatSession.session.approveToolCall(request.callId, request.decision);
        sendJson(exchange, "{}");
    }

    private void handleCancel(HttpServerExchange exchange, String sessionId) {
        var chatSession = sessionManager.getSession(sessionId);
        if (chatSession == null) {
            exchange.setStatusCode(404);
            sendJson(exchange, "{\"error\":\"session not found\"}");
            return;
        }

        chatSession.session.cancelTurn();
        sendJson(exchange, "{}");
    }

    private void handleClose(HttpServerExchange exchange, String sessionId) {
        sessionManager.closeSession(sessionId);
        sendJson(exchange, "{}");
    }

    private void handleLoadTools(HttpServerExchange exchange, String sessionId) {
        var chatSession = sessionManager.getSession(sessionId);
        if (chatSession == null) {
            exchange.setStatusCode(404);
            sendJson(exchange, "{\"error\":\"session not found\"}");
            return;
        }
        sendJson(exchange, "{\"loaded_tools\":[]}");
    }

    private void handleLoadSkills(HttpServerExchange exchange, String sessionId) {
        var chatSession = sessionManager.getSession(sessionId);
        if (chatSession == null) {
            exchange.setStatusCode(404);
            sendJson(exchange, "{\"error\":\"session not found\"}");
            return;
        }
        sendJson(exchange, "{\"loaded_skills\":[]}");
    }

    private String readBody(HttpServerExchange exchange) throws IOException {
        exchange.startBlocking();
        byte[] bytes = exchange.getInputStream().readAllBytes();
        if (bytes.length == 0) return null;
        return new String(bytes, StandardCharsets.UTF_8);
    }

    private void sendJson(HttpServerExchange exchange, String json) {
        exchange.getResponseHeaders().put(Headers.CONTENT_TYPE, "application/json");
        exchange.getResponseSender().send(json);
    }

    public static class SessionInfo {
        public String id;
        public String agentId;
        public List<String> loadedToolIds = new ArrayList<>();
        public List<String> loadedSkillIds = new ArrayList<>();
    }

    public static class HistoryMessage {
        public String role;
        public String content;

        public HistoryMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }

    public static class HistoryResponse {
        public List<HistoryMessage> messages;

        public HistoryResponse(List<HistoryMessage> messages) {
            this.messages = messages;
        }
    }

    public static class SimpleMessage {
        public String role;
        public String content;

        public SimpleMessage(String role, String content) {
            this.role = role;
            this.content = content;
        }
    }
}
