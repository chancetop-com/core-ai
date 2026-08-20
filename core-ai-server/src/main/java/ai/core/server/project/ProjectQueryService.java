package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.ProjectSubjectEvent;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.trace.domain.Trace;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side aggregations behind the project page tabs: executions, reports, members and the
 * narrative timeline. All project material is DERIVED from the member agent/workflow ids (no
 * binding fields on raw records); subject scoping joins the attribution table written by the
 * project agent analysis. Cost aggregation lives in {@link ProjectStatsQueryService}.
 *
 * @author stephen
 */
public class ProjectQueryService {
    static final int TIMELINE_MAX_ENTRIES = 200;

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
    MongoCollection<AgentDefinition> agentCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    MongoCollection<ProjectSubjectEvent> eventCollection;

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
        query.limit = 500;
        return eventCollection.find(query);
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

    public List<ProjectExecution> executions(String projectId, String type, String subjectId, int offset, int limit) {
        var scope = ProjectScope.resolve(projectCollection, projectId);
        if (scope == null) return List.of();
        var rows = new ArrayList<ProjectExecution>();
        if (type == null || "chat".equals(type)) rows.addAll(chatExecutions(scope, subjectId, offset, limit));
        if (type == null || "run".equals(type)) rows.addAll(runExecutions(scope, subjectId, offset, limit));
        if (type == null || "workflow".equals(type)) rows.addAll(workflowExecutions(scope, subjectId, offset, limit));
        rows.sort((a, b) -> compareDesc(a.startedAt, b.startedAt));
        return type == null && rows.size() > offset + limit ? rows.subList(offset, offset + limit) : rows;
    }

    public long executionCount(String projectId, String type, String subjectId) {
        var scope = ProjectScope.resolve(projectCollection, projectId);
        if (scope == null) return 0;
        if (type == null || "chat".equals(type)) return chatSessionCollection.count(sessionFilter(scope, subjectId));
        if ("run".equals(type)) return agentRunCollection.count(runFilter(scope, subjectId));
        return workflowRunCollection.count(workflowFilter(scope, subjectId));
    }

    public List<ProjectReport> reports(String projectId, String subjectId, String agentId) {
        var scope = ProjectScope.resolve(projectCollection, projectId);
        if (scope == null) return List.of();
        var byFile = new LinkedHashMap<String, ProjectReport>();
        addRunReports(byFile, scope, subjectId, agentId);
        addSessionReports(byFile, scope, subjectId, agentId);
        var subjectByFile = subjectByTarget(scope, "file");
        var names = agentNames(byFile.values().stream().map(ProjectReport::agentId).filter(id -> id != null && !id.isBlank()).distinct().toList());
        var result = new ArrayList<>(byFile.values().stream()
            .map(r -> new ProjectReport(r.fileId(), r.fileName(), r.contentType(), r.size(), r.createdAt(), subjectByFile.get(r.fileId()), r.agentId(), names.get(r.agentId())))
            .toList());
        result.sort((a, b) -> compareDesc(a.createdAt, b.createdAt));
        return result;
    }

    private Map<String, String> agentNames(List<String> agentIds) {
        var names = new HashMap<String, String>();
        if (agentIds.isEmpty()) return names;
        agentCollection.find(Filters.in("_id", agentIds)).forEach(a -> names.put(a.id, a.name));
        return names;
    }

    // membership queries (embedded members + addable options) live in ProjectMemberQueryService

    public List<TimelineEntry> timeline(String projectId, String subjectId) {
        var scope = ProjectScope.resolve(projectCollection, projectId);
        var entries = new ArrayList<TimelineEntry>();
        if (scope == null) return entries;
        var project = projectCollection.get(projectId).orElse(null);
        if (project == null) return entries;
        addKpiEntries(entries, project, subjectId);
        addNoteEntries(entries, project, subjectId);
        addActionItemEntries(entries, project, subjectId);
        addSubjectStatusEntries(entries, project, subjectId);
        var subjectBySession = subjectByTarget(scope, "session");
        for (var session : chatSessionCollection.find(sortedQuery(sessionFilter(scope, subjectId), "last_message_at"))) {
            if (session.title == null) continue;
            var at = session.lastMessageAt != null ? session.lastMessageAt : session.createdAt;
            entries.add(new TimelineEntry("session", session.title, null, subjectBySession.get(session.id), session.id, null, at));
        }
        var subjectByFile = subjectByTarget(scope, "file");
        for (var report : reports(projectId, subjectId, null)) {
            entries.add(new TimelineEntry("report", report.fileName, null, subjectByFile.get(report.fileId()), null, null, report.createdAt));
        }
        entries.sort((a, b) -> compareDesc(a.at, b.at));
        return entries.size() > TIMELINE_MAX_ENTRIES ? entries.subList(0, TIMELINE_MAX_ENTRIES) : entries;
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

    private void addSessionReports(Map<String, ProjectReport> byFile, ProjectScope scope, String subjectId, String agentId) {
        for (var session : chatSessionCollection.find(sortedQuery(sessionFilter(scope, subjectId), "last_message_at"))) {
            if (session.artifacts == null || !matchesAgent(session.agentId, agentId)) continue;
            for (var artifact : session.artifacts) {
                if (artifact.fileId == null) continue;
                var at = artifact.createdAt != null ? artifact.createdAt : session.lastMessageAt;
                byFile.putIfAbsent(artifact.fileId, new ProjectReport(artifact.fileId, artifact.fileName, artifact.contentType, artifact.size, at, null, session.agentId, null));
            }
        }
    }

    private void addRunReports(Map<String, ProjectReport> byFile, ProjectScope scope, String subjectId, String agentId) {
        for (var run : agentRunCollection.find(sortedQuery(runFilter(scope, subjectId), "started_at"))) {
            if (run.artifacts == null || !matchesAgent(run.agentId, agentId)) continue;
            for (var artifact : run.artifacts) {
                if (artifact.fileId == null) continue;
                var at = artifact.createdAt != null ? artifact.createdAt : run.startedAt;
                byFile.putIfAbsent(artifact.fileId, new ProjectReport(artifact.fileId, artifact.fileName, artifact.contentType, artifact.size, at, null, run.agentId, null));
            }
        }
    }

    private boolean matchesAgent(String actualAgentId, String filterAgentId) {
        return filterAgentId == null || filterAgentId.isBlank() || filterAgentId.equals(actualAgentId);
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
        var subjectIds = subjectPageSource(scope.projectId).stream().map(s -> s.id).toList();
        if (subjectIds.isEmpty()) return result;
        var query = new Query();
        query.filter = Filters.and(Filters.in("subject_id", subjectIds), Filters.eq("target_type", targetType));
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

    private void addKpiEntries(List<TimelineEntry> entries, Project project, String subjectId) {
        if (project.kpis == null) return;
        for (var kpi : project.kpis) {
            if (!matches(kpi.subjectId, subjectId)) continue;
            var title = kpi.key + " = " + kpi.value + (kpi.unit != null ? " " + kpi.unit : "");
            entries.add(new TimelineEntry("kpi", title, null, kpi.subjectId, null, null, kpi.createdAt));
        }
    }

    private void addNoteEntries(List<TimelineEntry> entries, Project project, String subjectId) {
        if (project.notes == null) return;
        for (var note : project.notes) {
            if (!matches(note.subjectId, subjectId)) continue;
            entries.add(new TimelineEntry("note", note.content, null, note.subjectId, null, null, note.createdAt));
        }
    }

    private void addActionItemEntries(List<TimelineEntry> entries, Project project, String subjectId) {
        if (project.actionItems == null) return;
        for (var item : project.actionItems) {
            if (!matches(item.subjectId, subjectId)) continue;
            entries.add(new TimelineEntry("action_item", item.title, item.status, item.subjectId, null, null,
                item.updatedAt != null ? item.updatedAt : item.createdAt));
        }
    }

    private void addSubjectStatusEntries(List<TimelineEntry> entries, Project project, String subjectId) {
        if (project.subjectStatuses == null) return;
        for (var status : project.subjectStatuses) {
            if (!matches(status.subjectId, subjectId)) continue;
            entries.add(new TimelineEntry("status", status.summary, status.phase, status.subjectId, null, null, status.updatedAt));
        }
    }

    private boolean matches(String recordSubjectId, String filterSubjectId) {
        return filterSubjectId == null || filterSubjectId.equals(recordSubjectId);
    }

    public record ProjectExecution(String id, String type, String title, String agentName, String status,
                                    ZonedDateTime startedAt, Long inputTokens, Long outputTokens, Double costUsd,
                                    String traceId, String subjectId) {
    }

    public record ProjectReport(String fileId, String fileName, String contentType, Long size,
                                ZonedDateTime createdAt, String subjectId, String agentId, String agentName) {
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
