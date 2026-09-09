package ai.core.server.project;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.trace.domain.Trace;
import ai.core.utils.JsonUtil;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side aggregations behind the project page tabs: executions, the event history and the
 * narrative timeline (reports live in {@link ProjectReportQueryService}, membership in
 * {@link ProjectMemberQueryService}, cost in {@link ProjectStatsQueryService}). All project material
 * is DERIVED from the member agent/workflow ids (no binding fields on raw records, no owner filter:
 * a project is a shared business container); subject scoping joins the attribution table.
 *
 * @author stephen
 */
public class ProjectQueryService {
    static final int TIMELINE_MAX_ENTRIES = 200;
    static final int EVENTS_MAX = 500;
    static final int STATE_EVENTS_MAX = 2000;

    static String metaValue(String meta, String field, String fallback) {
        if (meta == null || meta.isBlank()) return fallback;
        try {
            var map = JsonUtil.toMap(meta);
            var value = map != null ? map.get(field) : null;
            return value != null ? value.toString() : fallback;
        } catch (RuntimeException e) {
            return fallback;
        }
    }

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    MongoCollection<WorkflowRun> workflowRunCollection;
    @Inject
    MongoCollection<Trace> traceCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    MongoCollection<ProjectSubjectEvent> eventCollection;
    @Inject
    ProjectReportQueryService reportQueryService;

    // subject history rows (D7): the event series behind the timeline, trends and the report
    public List<ProjectSubjectEvent> events(String projectId, String subjectId, String type, ZonedDateTime from, ZonedDateTime to) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("project_id", projectId));
        if (subjectId != null && !subjectId.isBlank()) filters.add(Filters.eq("subject_id", subjectId));
        if (type != null && !type.isBlank()) filters.add(Filters.eq("type", type));
        if (from != null) filters.add(Filters.gte("at", from));
        if (to != null) filters.add(Filters.lte("at", to));
        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("at");
        query.limit = EVENTS_MAX;
        return eventCollection.find(query);
    }

    // the most recent events of one type in chronological order (the cockpit's KPI series / notes list)
    public List<ProjectSubjectEvent> stateEvents(String projectId, String subjectId, String type) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.eq("project_id", projectId));
        if (subjectId != null && !subjectId.isBlank()) filters.add(Filters.eq("subject_id", subjectId));
        filters.add(Filters.eq("type", type));
        var query = new Query();
        query.filter = Filters.and(filters);
        query.sort = Sorts.descending("at");
        query.limit = STATE_EVENTS_MAX;
        var rows = new ArrayList<>(eventCollection.find(query));
        rows.sort((a, b) -> compareDesc(b.at, a.at));
        return rows;
    }

    // search + pagination in memory over the project's subjects: subject counts are small and the
    // project_id equality is index-backed (notablescan-safe); no need for a regex-capable compound index
    public List<ProjectSubject> subjects(String projectId, int offset, int limit, String search) {
        var filtered = filterByName(subjectPageSource(projectId), search);
        var from = Math.min(offset, filtered.size());
        return filtered.subList(from, Math.min(offset + limit, filtered.size()));
    }

    public long subjectCount(String projectId, String search) {
        return filterByName(subjectPageSource(projectId), search).size();
    }

    public long attributionCount(String subjectId) {
        return attributionCollection.count(Filters.eq("subject_id", subjectId));
    }

    private List<ProjectSubject> subjectPageSource(String projectId) {
        var query = new Query();
        query.filter = Filters.eq("project_id", projectId);
        query.sort = Sorts.ascending("created_at");
        return subjectCollection.find(query);
    }

    private List<ProjectSubject> filterByName(List<ProjectSubject> all, String search) {
        if (search == null || search.isBlank()) return all;
        return all.stream().filter(s -> s.name != null
            && s.name.toLowerCase(java.util.Locale.ROOT).contains(search.trim().toLowerCase(java.util.Locale.ROOT))).toList();
    }

    // a single type pages in the database; the merged view fetches the first offset+limit rows of
    // every type, merges by time and slices ONCE (offset applied to the merged order only)
    public List<ProjectExecution> executions(String projectId, String type, String subjectId, int offset, int limit) {
        var scope = ProjectScope.resolve(projectCollection, projectId);
        if (scope == null) return List.of();
        if (type != null) {
            return switch (type) {
                case "chat" -> chatExecutions(scope, subjectId, offset, limit);
                case "run" -> runExecutions(scope, subjectId, offset, limit);
                case "workflow" -> workflowExecutions(scope, subjectId, offset, limit);
                default -> List.of();
            };
        }
        var window = offset + limit;
        var rows = new ArrayList<ProjectExecution>();
        rows.addAll(chatExecutions(scope, subjectId, 0, window));
        rows.addAll(runExecutions(scope, subjectId, 0, window));
        rows.addAll(workflowExecutions(scope, subjectId, 0, window));
        rows.sort((a, b) -> compareDesc(a.startedAt, b.startedAt));
        var from = Math.min(offset, rows.size());
        return rows.subList(from, Math.min(window, rows.size()));
    }

    public long executionCount(String projectId, String type, String subjectId) {
        var scope = ProjectScope.resolve(projectCollection, projectId);
        if (scope == null) return 0;
        long total = 0;
        if (type == null || "chat".equals(type)) total += chatSessionCollection.count(sessionFilter(scope, subjectId));
        if (type == null || "run".equals(type)) total += agentRunCollection.count(runFilter(scope, subjectId));
        if (type == null || "workflow".equals(type)) total += workflowRunCollection.count(workflowFilter(scope, subjectId));
        return total;
    }

    // narrative timeline: history events (authoritative) + member sessions + reports
    public List<TimelineEntry> timeline(String projectId, String subjectId) {
        var scope = ProjectScope.resolve(projectCollection, projectId);
        var entries = new ArrayList<TimelineEntry>();
        if (scope == null) return entries;
        for (var event : events(projectId, subjectId, null, null, null)) {
            var entry = toTimelineEntry(event);
            if (entry != null) entries.add(entry);
        }
        var subjectBySession = subjectByTarget(scope, "session");
        var sessions = sortedQuery(sessionFilter(scope, subjectId), "last_message_at");
        sessions.limit = TIMELINE_MAX_ENTRIES;
        for (var session : chatSessionCollection.find(sessions)) {
            if (session.title == null) continue;
            var at = session.lastMessageAt != null ? session.lastMessageAt : session.createdAt;
            entries.add(new TimelineEntry("session", session.title, null, subjectBySession.get(session.id), session.id, null, at));
        }
        for (var report : reportQueryService.reports(projectId, subjectId, TIMELINE_MAX_ENTRIES)) {
            entries.add(new TimelineEntry("report", report.fileName(), null, report.subjectId(), null, null, report.createdAt()));
        }
        entries.sort((a, b) -> compareDesc(a.at, b.at));
        return entries.size() > TIMELINE_MAX_ENTRIES ? entries.subList(0, TIMELINE_MAX_ENTRIES) : entries;
    }

    private TimelineEntry toTimelineEntry(ProjectSubjectEvent event) {
        return switch (event.type) {
            case ProjectSubjectEvent.TYPE_KPI -> new TimelineEntry("kpi", event.key + " = " + event.value + unitSuffix(event.meta), null, event.subjectId, null, null, event.at);
            case ProjectSubjectEvent.TYPE_NOTE -> new TimelineEntry("note", event.value, null, event.subjectId, null, null, event.at);
            case ProjectSubjectEvent.TYPE_ACTION_ITEM -> new TimelineEntry("action_item", metaValue(event.meta, "title", event.key), event.value, event.subjectId, null, null, event.at);
            case ProjectSubjectEvent.TYPE_PHASE -> new TimelineEntry("status", "entered phase " + event.value, event.value, event.subjectId, null, null, event.at);
            case ProjectSubjectEvent.TYPE_SUMMARY -> new TimelineEntry("status", event.value, event.key, event.subjectId, null, null, event.at);
            case ProjectSubjectEvent.TYPE_SUBJECT_STATUS -> new TimelineEntry("subject_status", "tracking " + event.value, event.value, event.subjectId, null, null, event.at);
            default -> null;
        };
    }

    private String unitSuffix(String meta) {
        var unit = metaValue(meta, "unit", null);
        return unit != null ? " " + unit : "";
    }

    private List<ProjectExecution> chatExecutions(ProjectScope scope, String subjectId, int offset, int limit) {
        var query = sortedQuery(sessionFilter(scope, subjectId), "last_message_at");
        query.skip = offset;
        query.limit = limit;
        var sessions = chatSessionCollection.find(query);
        var costs = sessionCosts(sessions.stream().map(s -> s.id).toList());
        var subjectBySession = subjectByTarget(scope, "session");
        var rows = new ArrayList<ProjectExecution>();
        for (var session : sessions) {
            var cost = costs.get(session.id);
            var title = session.title != null ? session.title : session.id;
            rows.add(new ProjectExecution(session.id, "chat", title, session.agentId, null,
                session.lastMessageAt != null ? session.lastMessageAt : session.createdAt,
                null, null, cost != null ? cost.cost : null, cost != null ? cost.traceId : null, subjectBySession.get(session.id)));
        }
        return rows;
    }

    private List<ProjectExecution> runExecutions(ProjectScope scope, String subjectId, int offset, int limit) {
        var query = sortedQuery(runFilter(scope, subjectId), "started_at");
        query.skip = offset;
        query.limit = limit;
        var runs = agentRunCollection.find(query);
        var costs = traceCosts(runs.stream().map(r -> r.traceId).filter(id -> id != null).toList());
        var subjectByRun = subjectByTarget(scope, "run");
        var rows = new ArrayList<ProjectExecution>();
        for (var run : runs) {
            var cost = run.traceId != null ? costs.get(run.traceId) : null;
            rows.add(new ProjectExecution(run.id, "run", run.input, run.agentId, run.status != null ? run.status.name() : null,
                run.startedAt, run.tokenUsage != null ? run.tokenUsage.input : null,
                run.tokenUsage != null ? run.tokenUsage.output : null, cost != null ? cost.cost : null, run.traceId, subjectByRun.get(run.id)));
        }
        return rows;
    }

    private List<ProjectExecution> workflowExecutions(ProjectScope scope, String subjectId, int offset, int limit) {
        var query = sortedQuery(workflowFilter(scope, subjectId), "started_at");
        query.skip = offset;
        query.limit = limit;
        var subjectByWorkflowRun = subjectByTarget(scope, "workflow_run");
        var rows = new ArrayList<ProjectExecution>();
        for (var run : workflowRunCollection.find(query)) {
            rows.add(new ProjectExecution(run.id, "workflow", run.input, run.workflowId, run.status != null ? run.status.name() : null,
                run.startedAt, run.tokenUsage != null ? run.tokenUsage.input : null,
                run.tokenUsage != null ? run.tokenUsage.output : null, null, null, subjectByWorkflowRun.get(run.id)));
        }
        return rows;
    }

    // aggregates cost/latest trace per session id (traces.session_id index)
    private Map<String, TraceCost> sessionCosts(List<String> sessionIds) {
        var result = new HashMap<String, TraceCost>();
        if (sessionIds.isEmpty()) return result;
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.in("session_id", sessionIds)),
            Aggregates.sort(Sorts.descending("started_at")),
            Aggregates.group("$session_id",
                Accumulators.sum("cost", "$cost_usd"),
                Accumulators.first("trace_id", "$trace_id")));
        for (var doc : traceCollection.aggregate(aggregate)) {
            result.put(doc.getString("_id"), new TraceCost(doc.getDouble("cost"), doc.getString("trace_id")));
        }
        return result;
    }

    private Map<String, TraceCost> traceCosts(List<String> traceIds) {
        var result = new HashMap<String, TraceCost>();
        if (traceIds.isEmpty()) return result;
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        aggregate.pipeline = List.of(
            Aggregates.match(Filters.in("trace_id", traceIds)),
            Aggregates.group("$trace_id",
                Accumulators.sum("cost", "$cost_usd")));
        for (var doc : traceCollection.aggregate(aggregate)) {
            result.put(doc.getString("_id"), new TraceCost(doc.getDouble("cost"), null));
        }
        return result;
    }

    private Bson sessionFilter(ProjectScope scope, String subjectId) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.in("agent_id", scope.agentIds));
        if (subjectId != null) {
            var ids = attributionTargets(subjectId, "session");
            filters.add(ids.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in("_id", ids));
        }
        filters.add(Filters.or(Filters.exists("deleted_at", false), Filters.eq("deleted_at", null)));
        return Filters.and(filters);
    }

    private Bson runFilter(ProjectScope scope, String subjectId) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.in("agent_id", scope.agentIds));
        if (subjectId != null) {
            var ids = attributionTargets(subjectId, "run");
            filters.add(ids.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in("_id", ids));
        }
        return Filters.and(filters);
    }

    private Bson workflowFilter(ProjectScope scope, String subjectId) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.in("workflow_id", scope.workflowIds));
        if (subjectId != null) {
            var ids = attributionTargets(subjectId, "workflow_run");
            filters.add(ids.isEmpty() ? Filters.eq("_id", "__none__") : Filters.in("_id", ids));
        }
        filters.add(Filters.or(Filters.exists("preview", false), Filters.eq("preview", Boolean.FALSE)));
        return Filters.and(filters);
    }

    private List<String> attributionTargets(String subjectId, String targetType) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("subject_id", subjectId), Filters.eq("target_type", targetType));
        return attributionCollection.find(query).stream().map(a -> a.targetId).toList();
    }

    private Map<String, String> subjectByTarget(ProjectScope scope, String targetType) {
        var result = new HashMap<String, String>();
        var query = new Query();
        query.filter = Filters.and(Filters.eq("project_id", scope.projectId), Filters.eq("target_type", targetType));
        for (var attribution : attributionCollection.find(query)) {
            result.putIfAbsent(attribution.targetId, attribution.subjectId);
        }
        return result;
    }

    private Query sortedQuery(Bson filter, String sortField) {
        var query = new Query();
        query.filter = filter;
        query.sort = Sorts.descending(sortField);
        return query;
    }

    private int compareDesc(ZonedDateTime left, ZonedDateTime right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return right.compareTo(left);
    }

    public record ProjectExecution(String id, String type, String title, String agentName, String status,
                                    ZonedDateTime startedAt, Long inputTokens, Long outputTokens, Double costUsd,
                                    String traceId, String subjectId) {
    }

    public record StatRow(String groupId, String name, Long tokens, Double costUsd, Long count) {
        public ai.core.server.domain.ProjectStatsItem toItem() {
            var item = new ai.core.server.domain.ProjectStatsItem();
            item.groupId = groupId;
            item.name = name;
            item.totalTokens = tokens;
            item.totalCostUsd = costUsd;
            item.traceCount = count;
            return item;
        }
    }

    public record ProjectStats(List<StatRow> totals, List<StatRow> byAgent, List<StatRow> bySubject) {
    }

    public record ProjectMember(String id, String name, String type) {
    }

    public record MemberOptions(List<ProjectMember> agents, List<ProjectMember> workflows) {
    }

    public record TimelineEntry(String type, String title, String detail, String subjectId,
                                String sessionId, String traceId, ZonedDateTime at) {
    }

    private record TraceCost(Double cost, String traceId) {
    }
}
