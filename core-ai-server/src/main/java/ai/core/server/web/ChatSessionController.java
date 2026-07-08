package ai.core.server.web;

import ai.core.server.domain.ChatSession;
import ai.core.server.domain.SessionFeedback;
import ai.core.server.sandbox.snapshot.SandboxSnapshotService;
import ai.core.server.session.ChatMessageService;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;

import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * @author Xander
 */
public class ChatSessionController {
    private static final ObjectMapper MAPPER = JsonUtil.OBJECT_MAPPER;

    @Inject
    WebContext webContext;
    @Inject
    ChatMessageService chatMessageService;
    @Inject
    SandboxSnapshotService sandboxSnapshotService;
    @Inject
    MongoCollection<SessionFeedback> sessionFeedbackCollection;

    public Response list(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);
        var params = request.queryParams();
        int offset = Integer.parseInt(params.getOrDefault("offset", "0"));
        int limit = Integer.parseInt(params.getOrDefault("limit", "50"));
        var sourcesParam = params.get("sources");
        var sources = sourcesParam != null && !sourcesParam.isEmpty()
            ? Arrays.asList(sourcesParam.split(","))
            : null;
        long total = chatMessageService.countSessions(userId, sources);
        var sessions = chatMessageService.listSessions(userId, sources, offset, limit, "created_at").stream()
            .map(this::toSummary)
            .toList();
        var result = new LinkedHashMap<String, Object>();
        result.put("sessions", sessions);
        result.put("total", total);
        return jsonResponse(result);
    }

    public Response get(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);
        var sessionId = request.pathParam("sessionId");
        var session = chatMessageService.getSessionMeta(sessionId);
        if (session == null || session.deletedAt != null) return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        if (session.userId != null && !userId.equals(session.userId)) return Response.text("forbidden").status(HTTPStatus.FORBIDDEN);
        return jsonResponse(toSummary(session));
    }

    public Response delete(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);
        var sessionId = request.pathParam("sessionId");
        var ok = chatMessageService.softDeleteSession(userId, sessionId);
        if (!ok) return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        sandboxSnapshotService.deleteForSession(sessionId);
        return jsonResponse(Map.of("deleted", true));
    }

    @SuppressWarnings("unchecked")
    public Response batchDelete(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);
        List<String> sessionIds;
        try {
            var bytes = request.body().get();
            if (bytes.length == 0) return Response.text("session_ids required").status(HTTPStatus.BAD_REQUEST);
            var map = MAPPER.readValue(bytes, Map.class);
            var raw = map.get("session_ids");
            if (!(raw instanceof List)) return Response.text("session_ids must be an array").status(HTTPStatus.BAD_REQUEST);
            sessionIds = (List<String>) raw;
        } catch (Exception e) {
            return Response.text("invalid request body").status(HTTPStatus.BAD_REQUEST);
        }
        if (sessionIds.isEmpty()) return Response.text("session_ids required").status(HTTPStatus.BAD_REQUEST);
        // Only clean snapshots of sessions actually soft-deleted: batchSoftDelete skips
        // non-owned/nonexistent ids, and snapshot deletion must not outrun that ownership check.
        var deletedIds = chatMessageService.batchSoftDelete(userId, sessionIds);
        deletedIds.forEach(sandboxSnapshotService::deleteForSession);
        return jsonResponse(Map.of("deleted", deletedIds.size()));
    }

    public Response update(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);
        var sessionId = request.pathParam("sessionId");
        var title = parseTitle(request);
        if (title == null || title.isBlank()) return Response.text("title required").status(HTTPStatus.BAD_REQUEST);
        var ok = chatMessageService.updateSessionTitle(userId, sessionId, title);
        if (!ok) return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        return jsonResponse(Map.of("updated", true));
    }

    @SuppressWarnings("unchecked")
    public Response submitFeedback(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);
        var sessionId = request.pathParam("sessionId");
        var session = chatMessageService.getSessionMeta(sessionId);
        if (session == null || session.deletedAt != null) return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        if (session.userId != null && !userId.equals(session.userId)) return Response.text("forbidden").status(HTTPStatus.FORBIDDEN);

        Map<String, Object> body;
        try {
            var bytes = request.body().get();
            if (bytes.length == 0) return Response.text("body required").status(HTTPStatus.BAD_REQUEST);
            body = MAPPER.readValue(bytes, Map.class);
        } catch (Exception e) {
            return Response.text("invalid request body").status(HTTPStatus.BAD_REQUEST);
        }

        var feedback = new SessionFeedback();
        feedback.id = UUID.randomUUID().toString();
        feedback.sessionId = sessionId;
        feedback.userId = userId;
        feedback.agentId = session.agentId;
        feedback.createdAt = ZonedDateTime.now();

        // Layer 1
        feedback.outcome = stringField(body, "outcome");

        // Layer 2
        feedback.failureReasons = listField(body, "failure_reasons");
        feedback.failureDetail = stringField(body, "failure_detail");

        // Layer 3
        feedback.understandingRating = intField(body, "understanding_rating");
        feedback.problemSolvingRating = intField(body, "problem_solving_rating");
        feedback.toolUsageRating = intField(body, "tool_usage_rating");
        feedback.communicationRating = intField(body, "communication_rating");
        feedback.outcomeRating = intField(body, "outcome_rating");

        // Layer 4
        feedback.proactivityFit = stringField(body, "proactivity_fit");
        feedback.decisionFit = stringField(body, "decision_fit");

        // Layer 5
        feedback.trustLevel = stringField(body, "trust_level");

        // Free text
        feedback.comment = stringField(body, "comment");

        // Auto-collected
        feedback.modelId = stringField(body, "model_id");
        feedback.tokenCount = longField(body, "token_count");
        feedback.sessionDurationMs = longField(body, "session_duration_ms");
        feedback.toolCallCount = intField(body, "tool_call_count");
        feedback.toolErrorCount = intField(body, "tool_error_count");
        feedback.messageCount = intField(body, "message_count");
        feedback.source = stringField(body, "source");

        sessionFeedbackCollection.insert(feedback);
        return jsonResponse(Map.of("id", feedback.id, "created", true));
    }

    private static String stringField(Map<String, Object> body, String key) {
        var val = body.get(key);
        return val == null ? null : val.toString();
    }

    @SuppressWarnings("unchecked")
    private static List<String> listField(Map<String, Object> body, String key) {
        var val = body.get(key);
        if (val instanceof List) return (List<String>) val;
        return null;
    }

    private static Integer intField(Map<String, Object> body, String key) {
        var val = body.get(key);
        if (val instanceof Number) return ((Number) val).intValue();
        return null;
    }

    private static Long longField(Map<String, Object> body, String key) {
        var val = body.get(key);
        if (val instanceof Number) return ((Number) val).longValue();
        return null;
    }

    private String parseTitle(Request request) {
        try {
            var body = request.body();
            if (body.isEmpty()) return null;
            var bytes = body.get();
            if (bytes.length == 0) return null;
            var map = MAPPER.readValue(bytes, Map.class);
            var title = map.get("title");
            return title == null ? null : title.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Object> toSummary(ChatSession s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", s.id);
        m.put("user_id", s.userId);
        m.put("agent_id", s.agentId);
        m.put("source", s.source);
        m.put("schedule_id", s.scheduleId);
        m.put("api_key_id", s.apiKeyId);
        m.put("title", s.title);
        m.put("message_count", s.messageCount);
        m.put("created_at", s.createdAt != null ? s.createdAt.toInstant().toString() : null);
        m.put("last_message_at", s.lastMessageAt != null ? s.lastMessageAt.toInstant().toString() : null);
        return m;
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
