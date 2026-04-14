package ai.core.server.web;

import ai.core.server.session.ChatMessageService;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;

import java.util.Map;

/**
 * @author Xander
 */
public class ChatSessionController {
    private static final ObjectMapper MAPPER = JsonUtil.OBJECT_MAPPER;

    @Inject
    WebContext webContext;
    @Inject
    ChatMessageService chatMessageService;

    public Response list(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "50"));
        var sessions = chatMessageService.listSessions(userId, offset, limit).stream().map(s -> {
            var m = new java.util.LinkedHashMap<String, Object>();
            m.put("id", s.id);
            m.put("user_id", s.userId);
            m.put("agent_id", s.agentId);
            m.put("title", s.title);
            m.put("message_count", s.messageCount);
            m.put("created_at", s.createdAt != null ? s.createdAt.toInstant().toString() : null);
            m.put("last_message_at", s.lastMessageAt != null ? s.lastMessageAt.toInstant().toString() : null);
            return m;
        }).toList();
        return jsonResponse(Map.of("sessions", sessions));
    }

    private Response jsonResponse(Object data) {
        try {
            var json = MAPPER.writeValueAsBytes(data);
            return Response.bytes(json).contentType(ContentType.APPLICATION_JSON);
        } catch (Exception e) {
            return Response.text("serialization error").status(HTTPStatus.INTERNAL_SERVER_ERROR);
        }
    }

}
