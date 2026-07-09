package ai.core.server.web.foryou;

import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.UserReport;
import ai.core.server.domain.UserTodo;
import ai.core.server.session.ChatMessageService;
import ai.core.server.trace.domain.Trace;
import ai.core.server.trace.domain.TraceDailyStats;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;

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

    private static long getLong(Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.longValue() : 0L;
    }

    private static double getDouble(Document doc, String key) {
        Number value = doc.get(key, Number.class);
        return value != null ? value.doubleValue() : 0.0;
    }

    @Inject
    MongoCollection<UserReport> reportCollection;

    @Inject
    MongoCollection<UserTodo> todoCollection;

    @Inject
    MongoCollection<FileRecord> fileCollection;

    @Inject
    MongoCollection<Trace> traceCollection;

    @Inject
    MongoCollection<TraceDailyStats> statsCollection;

    @Inject
    ChatMessageService chatMessageService;

    // --- Dashboard ---

    public DashboardData dashboard(String userId) {
        var data = new DashboardData();
        data.reportCount = reportCollection.count(Filters.eq("user_id", userId));
        data.todoCount = todoCollection.count(Filters.eq("user_id", userId));
        data.activeTodoCount = todoCollection.count(Filters.and(
            Filters.eq("user_id", userId),
            Filters.eq("completed", false)
        ));
        data.fileCount = fileCollection.count(Filters.eq("user_id", userId));

        data.recentSessions = chatMessageService.listSessions(userId, List.of("chat"), 0, 5);

        var reportQuery = new Query();
        reportQuery.filter = Filters.eq("user_id", userId);
        reportQuery.sort = Sorts.descending("updated_at");
        reportQuery.limit = 5;
        data.recentReports = reportCollection.find(reportQuery);

        var fileQuery = new Query();
        fileQuery.filter = Filters.eq("user_id", userId);
        fileQuery.sort = Sorts.descending("created_at");
        fileQuery.limit = 10;
        data.recentFiles = fileCollection.find(fileQuery);

        var todoQuery = new Query();
        todoQuery.filter = Filters.and(
            Filters.eq("user_id", userId),
            Filters.eq("completed", false)
        );
        todoQuery.sort = Sorts.ascending("created_at");
        data.activeTodos = todoCollection.find(todoQuery);

        return data;
    }

    // --- Reports ---

    public List<UserReport> listReports(String userId) {
        var query = new Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.descending("updated_at");
        return reportCollection.find(query);
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
        reportCollection.insert(report);
        return report;
    }

    public UserReport updateReport(String id, String userId, String title, String content, String type, List<String> tags) {
        var report = reportCollection.get(id).orElse(null);
        if (report == null || !userId.equals(report.userId)) return null;
        if (title != null) report.title = title;
        if (content != null) report.content = content;
        if (type != null) report.type = type;
        if (tags != null) report.tags = tags;
        report.updatedAt = ZonedDateTime.now();
        reportCollection.replace(report);
        return report;
    }

    public boolean deleteReport(String id, String userId) {
        var report = reportCollection.get(id).orElse(null);
        if (report == null || !userId.equals(report.userId)) return false;
        reportCollection.delete(id);
        return true;
    }

    // --- TODOs ---

    public List<UserTodo> listTodos(String userId) {
        var query = new Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.ascending("created_at");
        return todoCollection.find(query);
    }

    public UserTodo createTodo(String userId, String title, String description, String priority, ZonedDateTime dueDate) {
        var todo = new UserTodo();
        todo.id = UUID.randomUUID().toString();
        todo.userId = userId;
        todo.title = title;
        todo.description = description;
        todo.completed = false;
        todo.priority = priority;
        todo.dueDate = dueDate;
        todo.createdAt = ZonedDateTime.now();
        todo.updatedAt = todo.createdAt;
        todoCollection.insert(todo);
        return todo;
    }

    public UserTodo updateTodo(UpdateTodoRequest request) {
        var todo = todoCollection.get(request.id).orElse(null);
        if (todo == null || !request.userId.equals(todo.userId)) return null;
        if (request.title != null) todo.title = request.title;
        if (request.description != null) todo.description = request.description;
        if (request.completed != null) todo.completed = request.completed;
        if (request.priority != null) todo.priority = request.priority;
        if (request.dueDate != null) todo.dueDate = request.dueDate;
        todo.updatedAt = ZonedDateTime.now();
        todoCollection.replace(todo);
        return todo;
    }

    public boolean deleteTodo(String id, String userId) {
        var todo = todoCollection.get(id).orElse(null);
        if (todo == null || !userId.equals(todo.userId)) return false;
        todoCollection.delete(id);
        return true;
    }

    // --- Files ---

    public List<FileRecord> listFiles(String userId) {
        var query = new Query();
        query.filter = Filters.eq("user_id", userId);
        query.sort = Sorts.descending("created_at");
        return fileCollection.find(query);
    }

    // --- Token Usage ---

    @SuppressWarnings({"checkstyle:MethodLength", "checkstyle:ExecutableStatementCount"})
    public TokenUsageData tokenUsage(String userId, String range, String fromDate, String toDate) {
        LocalDate today = LocalDate.now(UTC);

        ZonedDateTime start;
        ZonedDateTime end;

        if (fromDate != null && !fromDate.isBlank() && toDate != null && !toDate.isBlank()) {
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
        var query = new Query();
        query.filter = Filters.and(
            Filters.eq("user_id", userId),
            Filters.gte("started_at", start),
            Filters.lt("started_at", end)
        );
        var traces = traceCollection.find(query);
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

    private void accumulateFromStats(Map<LocalDate, DailyUsage> dailyMap, String userId,
                                      LocalDate from, LocalDate to) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.and(
                Filters.eq("user_id", userId),
                Filters.gte("date", from.atStartOfDay(UTC)),
                Filters.lte("date", to.atStartOfDay(UTC))
            )),
            Aggregates.group(
                new Document("date", "$date"),
                Accumulators.sum("input_tokens", "$input_tokens"),
                Accumulators.sum("output_tokens", "$output_tokens"),
                Accumulators.sum("total_tokens", "$total_tokens"),
                Accumulators.sum("cached_tokens", "$cached_tokens"),
                Accumulators.sum("cost_usd", "$cost_usd")
            )
        );
        var rows = statsCollection.aggregate(aggregate);
        for (var row : rows) {
            var dateDoc = row.get("_id", Document.class);
            if (dateDoc == null) continue;
            Object dateObj = dateDoc.get("date");
            if (!(dateObj instanceof java.util.Date d)) continue;
            LocalDate date = d.toInstant().atZone(UTC).toLocalDate();
            var daily = dailyMap.computeIfAbsent(date, k -> new DailyUsage());

            daily.inputTokens += getLong(row, "input_tokens");
            daily.outputTokens += getLong(row, "output_tokens");
            daily.totalTokens += getLong(row, "total_tokens");
            daily.cachedTokens += getLong(row, "cached_tokens");
            daily.costUsd += getDouble(row, "cost_usd");
        }
    }

    private TokenUsageData buildResult(Map<LocalDate, DailyUsage> dailyMap) {
        long totalInput = 0;
        long totalOutput = 0;
        long totalCached = 0;
        double totalCost = 0;

        var data = new TokenUsageData();
        data.daily = new ArrayList<>();
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
