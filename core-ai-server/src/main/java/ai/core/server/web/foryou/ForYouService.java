package ai.core.server.web.foryou;

import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.UserReport;
import ai.core.server.domain.UserTodo;
import ai.core.server.session.ChatMessageService;
import ai.core.server.trace.TraceDailyStatsStore;
import ai.core.server.trace.TraceQuery;
import ai.core.server.trace.TraceStore;
import core.framework.inject.Inject;

import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

public class ForYouService {
    private static final ZoneId UTC = ZoneId.of("UTC");

    @Inject
    UserReportStore reportStore;

    @Inject
    UserTodoStore todoStore;

    @Inject
    ai.core.server.file.FileRecordStore fileRecordStore;

    @Inject
    TraceStore traceStore;

    @Inject
    TraceDailyStatsStore dailyStatsStore;

    @Inject
    ChatMessageService chatMessageService;

    // --- Dashboard ---

    public DashboardData dashboard(String userId) {
        var data = new DashboardData();
        data.reportCount = reportStore.count(userId);
        data.todoCount = todoStore.count(userId);
        data.activeTodoCount = todoStore.activeCount(userId);
        var fileQuery = new ai.core.server.file.FileRecordQuery();
        fileQuery.userId = userId;
        data.fileCount = fileRecordStore.count(fileQuery);

        data.recentSessions = chatMessageService.listSessions(userId, List.of("chat"), 0, 5);

        data.recentReports = reportStore.recent(userId, 5);

        fileQuery.limit = 10;
        data.recentFiles = fileRecordStore.find(fileQuery);

        data.activeTodos = todoStore.active(userId);

        return data;
    }

    // --- Reports ---

    public List<UserReport> listReports(String userId) {
        return reportStore.list(userId);
    }

    public UserReport createReport(String userId, String title, String content, String type, List<String> tags) {
        var report = new UserReport();
        report.id = UUID.randomUUID().toString();
        report.userId = userId;
        report.title = title;
        report.content = content;
        report.type = type;
        report.tags = tags;
        report.createdAt = ZonedDateTime.now();
        report.updatedAt = report.createdAt;
        reportStore.insert(report);
        return report;
    }

    public UserReport updateReport(String id, String userId, String title, String content, String type, List<String> tags) {
        var report = reportStore.get(id).orElse(null);
        if (report == null || !userId.equals(report.userId)) return null;
        if (title != null) report.title = title;
        if (content != null) report.content = content;
        if (type != null) report.type = type;
        if (tags != null) report.tags = tags;
        report.updatedAt = ZonedDateTime.now();
        reportStore.replace(report);
        return report;
    }

    public boolean deleteReport(String id, String userId) {
        var report = reportStore.get(id).orElse(null);
        if (report == null || !userId.equals(report.userId)) return false;
        reportStore.delete(id);
        return true;
    }

    // --- TODOs ---

    public List<UserTodo> listTodos(String userId) {
        return todoStore.list(userId);
    }

    public UserTodo createTodo(String userId, String title, String description, String priority, ZonedDateTime dueDate) {
        var todo = new UserTodo();
        todo.id = UUID.randomUUID().toString();
        todo.userId = userId;
        todo.title = title;
        todo.description = description;
        todo.completed = Boolean.FALSE;
        todo.priority = priority;
        todo.dueDate = dueDate;
        todo.createdAt = ZonedDateTime.now();
        todo.updatedAt = todo.createdAt;
        todoStore.insert(todo);
        return todo;
    }

    public UserTodo updateTodo(UpdateTodoRequest request) {
        var todo = todoStore.get(request.id).orElse(null);
        if (todo == null || !request.userId.equals(todo.userId)) return null;
        if (request.title != null) todo.title = request.title;
        if (request.description != null) todo.description = request.description;
        if (request.completed != null) todo.completed = request.completed;
        if (request.priority != null) todo.priority = request.priority;
        if (request.dueDate != null) todo.dueDate = request.dueDate;
        todo.updatedAt = ZonedDateTime.now();
        todoStore.replace(todo);
        return todo;
    }

    public boolean deleteTodo(String id, String userId) {
        var todo = todoStore.get(id).orElse(null);
        if (todo == null || !userId.equals(todo.userId)) return false;
        todoStore.delete(id);
        return true;
    }

    // --- Files ---

    public List<FileRecord> listFiles(String userId) {
        var query = new ai.core.server.file.FileRecordQuery();
        query.userId = userId;
        query.limit = null;
        return fileRecordStore.find(query);
    }

    // --- Token Usage ---

    public TokenUsageData tokenUsage(String userId, String range, String fromDate, String toDate) {
        LocalDate today = LocalDate.now(UTC);
        DateRange dateRange = resolveDateRange(range, fromDate, toDate);
        ZonedDateTime start = dateRange.start;
        ZonedDateTime end = dateRange.end;

        Map<LocalDate, DailyUsage> dailyMap = new TreeMap<>();

        // Today: live traces (not yet aggregated into trace_daily_stats)
        boolean includeToday = end.isAfter(today.atStartOfDay(UTC))
            && !start.isAfter(today.atStartOfDay(UTC));
        if (includeToday) {
            accumulateFromTraces(dailyMap, userId, today.atStartOfDay(UTC), end);
        }

        // Historical: trace_daily_stats (pre-aggregated, dates strictly before today)
        LocalDate historicalEnd = end.minusSeconds(1).toLocalDate();
        if (!historicalEnd.isBefore(today)) {
            historicalEnd = today.minusDays(1);
        }
        boolean includeHistorical = !start.toLocalDate().isAfter(historicalEnd);
        if (includeHistorical) {
            accumulateFromStats(dailyMap, userId, start.toLocalDate(), historicalEnd);
        }

        return buildResult(dailyMap);
    }

    private void accumulateFromTraces(Map<LocalDate, DailyUsage> dailyMap, String userId,
                                       ZonedDateTime start, ZonedDateTime end) {
        var query = new TraceQuery();
        query.userId = userId;
        query.startFrom = start;
        query.startBefore = end;
        var traces = traceStore.list(query);
        for (var trace : traces) {
            if (trace.startedAt == null) continue;
            var date = trace.startedAt.withZoneSameInstant(UTC).toLocalDate();
            var daily = dailyMap.computeIfAbsent(date, d -> new DailyUsage());

            long input = trace.inputTokens != null ? trace.inputTokens : 0;
            long output = trace.outputTokens != null ? trace.outputTokens : 0;
            long cached = trace.cachedTokens != null ? trace.cachedTokens : 0;
            double cost = trace.costUsd != null ? trace.costUsd : 0;

            daily.inputTokens += input;
            daily.outputTokens += output;
            daily.totalTokens += trace.totalTokens != null ? trace.totalTokens : (input + output);
            daily.cachedTokens += cached;
            daily.costUsd += cost;
        }
    }

    private DateRange resolveDateRange(String range, String fromDate, String toDate) {
        ZonedDateTime start;
        ZonedDateTime end;

        if (fromDate != null && toDate != null && !fromDate.isBlank() && !toDate.isBlank()) {
            start = LocalDate.parse(fromDate).atStartOfDay(UTC);
            end = LocalDate.parse(toDate).plusDays(1).atStartOfDay(UTC);
        } else {
            var now = ZonedDateTime.now(UTC);
            int days = switch (range) {
                case "yesterday" -> 1;
                case "7d" -> 7;
                case "30d" -> 30;
                default -> 7;
            };
            start = now.minusDays(days).withHour(0).withMinute(0).withSecond(0).withNano(0);
            end = now;
        }
        return new DateRange(start, end);
    }

    private void accumulateFromStats(Map<LocalDate, DailyUsage> dailyMap, String userId,
                                       LocalDate from, LocalDate to) {
        var rows = dailyStatsStore.dailyUsage(userId, from.atStartOfDay(UTC), to.atStartOfDay(UTC));
        for (var row : rows) {
            if (row.date == null) continue;
            LocalDate date = row.date.withZoneSameInstant(UTC).toLocalDate();
            var daily = dailyMap.computeIfAbsent(date, k -> new DailyUsage());
            daily.inputTokens += row.inputTokens != null ? row.inputTokens : 0L;
            daily.outputTokens += row.outputTokens != null ? row.outputTokens : 0L;
            daily.totalTokens += row.totalTokens != null ? row.totalTokens : 0L;
            daily.cachedTokens += row.cachedTokens != null ? row.cachedTokens : 0L;
            daily.costUsd += row.costUsd != null ? row.costUsd : 0.0;
        }
    }

    private TokenUsageData buildResult(Map<LocalDate, DailyUsage> dailyMap) {
        long totalInput = 0;
        long totalOutput = 0;
        long totalCached = 0;
        double totalCost = 0;

        var data = new TokenUsageData();
        data.daily = new ArrayList<>(dailyMap.size());
        for (var entry : dailyMap.entrySet()) {
            var d = entry.getValue();
            var item = new DailyUsageItem();
            item.date = entry.getKey().toString();
            item.inputTokens = d.inputTokens;
            item.outputTokens = d.outputTokens;
            item.totalTokens = d.totalTokens;
            item.cachedTokens = d.cachedTokens;
            item.costUsd = Math.round(d.costUsd * 10000.0) / 10000.0;
            data.daily.add(item);

            totalInput += d.inputTokens;
            totalOutput += d.outputTokens;
            totalCached += d.cachedTokens;
            totalCost += d.costUsd;
        }

        data.totalInputTokens = totalInput;
        data.totalOutputTokens = totalOutput;
        data.totalTokens = totalInput + totalOutput;
        data.totalCachedTokens = totalCached;
        data.totalCostUsd = Math.round(totalCost * 10000.0) / 10000.0;
        return data;
    }

    private record DateRange(ZonedDateTime start, ZonedDateTime end) {

    }

    private static final class DailyUsage {
        long inputTokens;
        long outputTokens;
        long totalTokens;
        long cachedTokens;
        double costUsd;
    }

    public static class TokenUsageData {
        public long totalInputTokens;
        public long totalOutputTokens;
        public long totalTokens;
        public long totalCachedTokens;
        public double totalCostUsd;
        public List<DailyUsageItem> daily;
    }

    public static class DailyUsageItem {
        public String date;
        public long inputTokens;
        public long outputTokens;
        public long totalTokens;
        public long cachedTokens;
        public double costUsd;
    }

    // --- Dashboard DTO ---

    public static class DashboardData {
        public long reportCount;
        public long todoCount;
        public long activeTodoCount;
        public long fileCount;
        public List<ChatSession> recentSessions;
        public List<UserReport> recentReports;
        public List<UserTodo> activeTodos;
        public List<FileRecord> recentFiles;
    }

    public record UpdateTodoRequest(String id, String userId, String title, String description,
                                     Boolean completed, String priority, ZonedDateTime dueDate) { }
}
