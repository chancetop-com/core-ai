package ai.core.server.web.foryou;

import ai.core.api.server.foryou.DeleteForYouItemResponse;
import ai.core.api.server.foryou.ForYouDailyUsageView;
import ai.core.api.server.foryou.ForYouDashboardView;
import ai.core.api.server.foryou.ForYouFileView;
import ai.core.api.server.foryou.ForYouReportRequest;
import ai.core.api.server.foryou.ForYouReportView;
import ai.core.api.server.foryou.ForYouTodoRequest;
import ai.core.api.server.foryou.ForYouTodoView;
import ai.core.api.server.foryou.ForYouTokenUsageRequest;
import ai.core.api.server.foryou.ForYouTokenUsageView;
import ai.core.api.server.foryou.ForYouWebService;
import ai.core.api.server.foryou.ListForYouFilesResponse;
import ai.core.api.server.foryou.ListForYouReportsResponse;
import ai.core.api.server.foryou.ListForYouTodosResponse;
import ai.core.api.server.session.ChatSessionSummaryView;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.UserReport;
import ai.core.server.domain.UserTodo;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import ai.core.server.web.foryou.ForYouService.DailyUsageItem;
import ai.core.server.web.foryou.ForYouService.TokenUsageData;
import ai.core.server.web.foryou.ForYouService.UpdateTodoRequest;
import core.framework.inject.Inject;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.NotFoundException;
import core.framework.web.exception.UnauthorizedException;

import java.time.ZonedDateTime;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.DASHBOARD_VIEW)
public class ForYouWebServiceImpl implements ForYouWebService {
    @Inject
    WebContext webContext;

    @Inject
    ForYouService forYouService;

    @Override
    public ForYouDashboardView dashboard() {
        var data = forYouService.dashboard(userId());
        var view = new ForYouDashboardView();
        view.reportCount = data.reportCount;
        view.todoCount = data.todoCount;
        view.activeTodoCount = data.activeTodoCount;
        view.fileCount = data.fileCount;
        view.recentSessions = data.recentSessions.stream().map(this::toSessionView).toList();
        view.recentReports = data.recentReports.stream().map(this::toReportView).toList();
        view.activeTodos = data.activeTodos.stream().map(this::toTodoView).toList();
        view.recentFiles = data.recentFiles.stream().map(this::toFileView).toList();
        return view;
    }

    @Override
    public ListForYouReportsResponse listReports() {
        var reports = forYouService.listReports(userId());
        var response = new ListForYouReportsResponse();
        response.reports = reports.stream().map(this::toReportView).toList();
        return response;
    }

    @Override
    public ForYouReportView createReport(ForYouReportRequest request) {
        if (request.title == null || request.title.isBlank()) throw new BadRequestException("title is required");
        var report = forYouService.createReport(userId(), request.title, request.content, request.type, request.tags);
        return toReportView(report);
    }

    @Override
    public ForYouReportView updateReport(String id, ForYouReportRequest request) {
        var report = forYouService.updateReport(id, userId(), request.title, request.content, request.type, request.tags);
        if (report == null) throw new NotFoundException("report not found");
        return toReportView(report);
    }

    @Override
    public DeleteForYouItemResponse deleteReport(String id) {
        var ok = forYouService.deleteReport(id, userId());
        if (!ok) throw new NotFoundException("report not found");
        return deleted();
    }

    @Override
    public ListForYouTodosResponse listTodos() {
        var todos = forYouService.listTodos(userId());
        var response = new ListForYouTodosResponse();
        response.todos = todos.stream().map(this::toTodoView).toList();
        return response;
    }

    @Override
    public ForYouTodoView createTodo(ForYouTodoRequest request) {
        if (request.title == null || request.title.isBlank()) throw new BadRequestException("title is required");
        var dueDate = request.dueDate != null ? ZonedDateTime.parse(request.dueDate) : null;
        var todo = forYouService.createTodo(userId(), request.title, request.description, request.priority, dueDate);
        return toTodoView(todo);
    }

    @Override
    public ForYouTodoView updateTodo(String id, ForYouTodoRequest request) {
        var dueDate = request.dueDate != null ? ZonedDateTime.parse(request.dueDate) : null;
        var todo = forYouService.updateTodo(new UpdateTodoRequest(
            id, userId(), request.title, request.description, request.completed, request.priority, dueDate));
        if (todo == null) throw new NotFoundException("todo not found");
        return toTodoView(todo);
    }

    @Override
    public DeleteForYouItemResponse deleteTodo(String id) {
        var ok = forYouService.deleteTodo(id, userId());
        if (!ok) throw new NotFoundException("todo not found");
        return deleted();
    }

    @Override
    public ListForYouFilesResponse listFiles() {
        var files = forYouService.listFiles(userId());
        var response = new ListForYouFilesResponse();
        response.files = files.stream().map(this::toFileView).toList();
        return response;
    }

    @Override
    public ForYouTokenUsageView tokenUsage(ForYouTokenUsageRequest request) {
        var data = forYouService.tokenUsage(userId(), request.range != null ? request.range : "7d",
            request.from, request.to);
        return toTokenUsageView(data);
    }

    private ChatSessionSummaryView toSessionView(ChatSession s) {
        var view = new ChatSessionSummaryView();
        view.id = s.id;
        view.userId = s.userId;
        view.agentId = s.agentId;
        view.source = s.source;
        view.title = s.title;
        view.messageCount = s.messageCount;
        view.createdAt = formatTime(s.createdAt);
        view.lastMessageAt = formatTime(s.lastMessageAt);
        return view;
    }

    private ForYouReportView toReportView(UserReport r) {
        var view = new ForYouReportView();
        view.id = r.id;
        view.userId = r.userId;
        view.title = r.title;
        view.content = r.content;
        view.type = r.type;
        view.tags = r.tags;
        view.createdAt = formatTime(r.createdAt);
        view.updatedAt = formatTime(r.updatedAt);
        return view;
    }

    private ForYouTodoView toTodoView(UserTodo t) {
        var view = new ForYouTodoView();
        view.id = t.id;
        view.userId = t.userId;
        view.title = t.title;
        view.description = t.description;
        view.completed = t.completed;
        view.priority = t.priority;
        view.dueDate = formatTime(t.dueDate);
        view.createdAt = formatTime(t.createdAt);
        view.updatedAt = formatTime(t.updatedAt);
        return view;
    }

    private ForYouFileView toFileView(FileRecord f) {
        var view = new ForYouFileView();
        view.id = f.id;
        view.userId = f.userId;
        view.fileName = f.fileName;
        view.contentType = f.contentType;
        view.size = f.size;
        view.createdAt = formatTime(f.createdAt);
        return view;
    }

    private ForYouTokenUsageView toTokenUsageView(TokenUsageData data) {
        var view = new ForYouTokenUsageView();
        view.totalInputTokens = data.totalInputTokens;
        view.totalOutputTokens = data.totalOutputTokens;
        view.totalTokens = data.totalTokens;
        view.totalCachedTokens = data.totalCachedTokens;
        view.totalCostUsd = data.totalCostUsd;
        view.daily = data.daily.stream().map(this::toDailyUsageView).toList();
        return view;
    }

    private ForYouDailyUsageView toDailyUsageView(DailyUsageItem item) {
        var view = new ForYouDailyUsageView();
        view.date = item.date;
        view.inputTokens = item.inputTokens;
        view.outputTokens = item.outputTokens;
        view.totalTokens = item.totalTokens;
        view.cachedTokens = item.cachedTokens;
        view.costUsd = item.costUsd;
        return view;
    }

    private DeleteForYouItemResponse deleted() {
        var response = new DeleteForYouItemResponse();
        response.deleted = Boolean.TRUE;
        return response;
    }

    private String formatTime(ZonedDateTime time) {
        return time != null ? time.toInstant().toString() : null;
    }

    private String userId() {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        return userId;
    }
}
