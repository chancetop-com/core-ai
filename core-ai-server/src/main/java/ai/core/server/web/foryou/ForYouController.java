package ai.core.server.web.foryou;

import ai.core.server.domain.FileRecord;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.UserReport;
import ai.core.server.domain.UserTodo;
import ai.core.server.web.auth.AuthContext;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import core.framework.api.http.HTTPStatus;
import core.framework.http.ContentType;
import core.framework.inject.Inject;
import core.framework.web.Request;
import core.framework.web.Response;
import core.framework.web.WebContext;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ForYouController {
    private static final ObjectMapper MAPPER = JsonUtil.OBJECT_MAPPER;

    @Inject
    WebContext webContext;

    @Inject
    ForYouService forYouService;

    // --- Dashboard ---

    public Response dashboard(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        var data = forYouService.dashboard(userId);
        var result = new LinkedHashMap<String, Object>();
        result.put("report_count", data.reportCount);
        result.put("todo_count", data.todoCount);
        result.put("active_todo_count", data.activeTodoCount);
        result.put("file_count", data.fileCount);
        result.put("recent_sessions", data.recentSessions.stream().map(this::toSessionMap).toList());
        result.put("recent_reports", data.recentReports.stream().map(this::toReportMap).toList());
        result.put("active_todos", data.activeTodos.stream().map(this::toTodoMap).toList());
        result.put("recent_files", data.recentFiles.stream().map(this::toFileMap).toList());
        return jsonResponse(result);
    }

    // --- Reports ---

    public Response listReports(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        var reports = forYouService.listReports(userId);
        var result = new LinkedHashMap<String, Object>();
        result.put("reports", reports.stream().map(this::toReportMap).toList());
        return jsonResponse(result);
    }

    public Response createReport(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        @SuppressWarnings("unchecked")
        var body = parseBody(request);
        var title = (String) body.get("title");
        if (title == null || title.isBlank()) {
            return Response.text("title is required").status(HTTPStatus.BAD_REQUEST);
        }
        var content = (String) body.get("content");
        var type = (String) body.get("type");
        @SuppressWarnings("unchecked")
        var tags = (List<String>) body.get("tags");

        var report = forYouService.createReport(userId, title, content, type, tags);
        return jsonResponse(Map.of("report", toReportMap(report)));
    }

    public Response updateReport(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        var id = request.pathParam("id");
        @SuppressWarnings("unchecked")
        var body = parseBody(request);
        var title = (String) body.get("title");
        var content = (String) body.get("content");
        var type = (String) body.get("type");
        @SuppressWarnings("unchecked")
        var tags = (List<String>) body.get("tags");

        var report = forYouService.updateReport(id, userId, title, content, type, tags);
        if (report == null) return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        return jsonResponse(Map.of("report", toReportMap(report)));
    }

    public Response deleteReport(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        var id = request.pathParam("id");
        var ok = forYouService.deleteReport(id, userId);
        if (!ok) return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        return jsonResponse(Map.of("deleted", Boolean.TRUE));
    }

    // --- TODOs ---

    public Response listTodos(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        var todos = forYouService.listTodos(userId);
        var result = new LinkedHashMap<String, Object>();
        result.put("todos", todos.stream().map(this::toTodoMap).toList());
        return jsonResponse(result);
    }

    public Response createTodo(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        @SuppressWarnings("unchecked")
        var body = parseBody(request);
        var title = (String) body.get("title");
        if (title == null || title.isBlank()) {
            return Response.text("title is required").status(HTTPStatus.BAD_REQUEST);
        }
        var description = (String) body.get("description");
        var priority = (String) body.get("priority");
        var dueDateStr = (String) body.get("due_date");
        ZonedDateTime dueDate = dueDateStr != null ? ZonedDateTime.parse(dueDateStr) : null;

        var todo = forYouService.createTodo(userId, title, description, priority, dueDate);
        return jsonResponse(Map.of("todo", toTodoMap(todo)));
    }

    public Response updateTodo(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        var id = request.pathParam("id");
        @SuppressWarnings("unchecked")
        var body = parseBody(request);
        var title = (String) body.get("title");
        var description = (String) body.get("description");
        var completed = body.get("completed") != null ? (Boolean) body.get("completed") : null;
        var priority = (String) body.get("priority");
        var dueDateStr = (String) body.get("due_date");
        ZonedDateTime dueDate = dueDateStr != null ? ZonedDateTime.parse(dueDateStr) : null;

        var todo = forYouService.updateTodo(new ForYouService.UpdateTodoRequest(id, userId, title, description, completed, priority, dueDate));
        if (todo == null) return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        return jsonResponse(Map.of("todo", toTodoMap(todo)));
    }

    public Response deleteTodo(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        var id = request.pathParam("id");
        var ok = forYouService.deleteTodo(id, userId);
        if (!ok) return Response.text("not found").status(HTTPStatus.NOT_FOUND);
        return jsonResponse(Map.of("deleted", Boolean.TRUE));
    }

    // --- Files ---

    public Response listFiles(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        var files = forYouService.listFiles(userId);
        var result = new LinkedHashMap<String, Object>();
        result.put("files", files.stream().map(this::toFileMap).toList());
        return jsonResponse(result);
    }

    // --- Token Usage ---

    public Response tokenUsage(Request request) {
        var userId = AuthContext.userId(webContext);
        if (userId == null) return Response.text("unauthorized").status(HTTPStatus.UNAUTHORIZED);

        var range = request.queryParams().getOrDefault("range", "7d");
        var from = request.queryParams().get("from");
        var to = request.queryParams().get("to");
        var data = forYouService.tokenUsage(userId, range, from, to);

        var result = new LinkedHashMap<String, Object>();
        result.put("total_input_tokens", data.totalInputTokens);
        result.put("total_output_tokens", data.totalOutputTokens);
        result.put("total_tokens", data.totalTokens);
        result.put("total_cached_tokens", data.totalCachedTokens);
        result.put("total_cost_usd", data.totalCostUsd);

        var dailyList = new ArrayList<Map<String, Object>>();
        for (var item : data.daily) {
            var m = new LinkedHashMap<String, Object>();
            m.put("date", item.date);
            m.put("input_tokens", item.inputTokens);
            m.put("output_tokens", item.outputTokens);
            m.put("total_tokens", item.totalTokens);
            m.put("cached_tokens", item.cachedTokens);
            m.put("cost_usd", item.costUsd);
            dailyList.add(m);
        }
        result.put("daily", dailyList);
        return jsonResponse(result);
    }

    // --- Serialization helpers ---

    private Map<String, Object> toSessionMap(ChatSession s) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", s.id);
        m.put("user_id", s.userId);
        m.put("agent_id", s.agentId);
        m.put("source", s.source);
        m.put("title", s.title);
        m.put("message_count", s.messageCount);
        m.put("created_at", formatTime(s.createdAt));
        m.put("last_message_at", formatTime(s.lastMessageAt));
        return m;
    }

    private Map<String, Object> toReportMap(UserReport r) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", r.id);
        m.put("user_id", r.userId);
        m.put("title", r.title);
        m.put("content", r.content);
        m.put("type", r.type);
        m.put("tags", r.tags);
        m.put("created_at", formatTime(r.createdAt));
        m.put("updated_at", formatTime(r.updatedAt));
        return m;
    }

    private Map<String, Object> toTodoMap(UserTodo t) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", t.id);
        m.put("user_id", t.userId);
        m.put("title", t.title);
        m.put("description", t.description);
        m.put("completed", t.completed);
        m.put("priority", t.priority);
        m.put("due_date", formatTime(t.dueDate));
        m.put("created_at", formatTime(t.createdAt));
        m.put("updated_at", formatTime(t.updatedAt));
        return m;
    }

    private Map<String, Object> toFileMap(FileRecord f) {
        var m = new LinkedHashMap<String, Object>();
        m.put("id", f.id);
        m.put("user_id", f.userId);
        m.put("file_name", f.fileName);
        m.put("content_type", f.contentType);
        m.put("size", f.size);
        m.put("created_at", formatTime(f.createdAt));
        return m;
    }

    private String formatTime(ZonedDateTime time) {
        return time != null ? time.toInstant().toString() : null;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseBody(Request request) {
        try {
            var body = request.body();
            if (body.isEmpty()) return Map.of();
            var bytes = body.get();
            if (bytes.length == 0) return Map.of();
            return MAPPER.readValue(bytes, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("invalid JSON body", e);
        }
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
