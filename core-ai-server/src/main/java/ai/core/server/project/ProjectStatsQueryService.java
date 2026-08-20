package ai.core.server.project;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectStatsData;
import ai.core.server.domain.ProjectStatsEntity;
import ai.core.server.domain.ProjectStatsItem;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.ProjectSubjectStats;
import ai.core.server.trace.domain.Trace;
import com.mongodb.client.model.Accumulators;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.BsonField;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.Aggregate;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * The cost lens behind the project cockpit. Stats are NOT aggregated per request: the stats refresh
 * job (or any update path) computes a snapshot and caches it on the project document, and the API
 * reads that snapshot. Recomputing is triggered by marking the project dirty (member changes,
 * attribution advances, analysis completes).
 *
 * @author stephen
 */
public class ProjectStatsQueryService {
    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectStatsEntity> statsCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    MongoCollection<Trace> traceCollection;

    // reads the cached snapshot; subjectId != null returns that subject's breakdown from the cache
    public ProjectQueryService.ProjectStats stats(String projectId, String subjectId) {
        var project = statsCollection.get(projectId).orElse(null);
        var snapshot = project != null ? project.stats : null;
        if (snapshot == null) {
            return new ProjectQueryService.ProjectStats(List.of(), List.of(), List.of());
        }
        if (subjectId != null && !subjectId.isBlank()) {
            for (var subject : snapshot.subjects == null ? List.<ProjectSubjectStats>of() : snapshot.subjects) {
                if (subjectId.equals(subject.subjectId)) {
                    var totals = subject.traceCount != null
                        ? List.of(new ProjectQueryService.StatRow(subjectId, null, subject.totalTokens, subject.totalCostUsd, subject.traceCount))
                        : List.<ProjectQueryService.StatRow>of();
                    return new ProjectQueryService.ProjectStats(totals, toRows(subject.byAgent), List.of());
                }
            }
            return new ProjectQueryService.ProjectStats(List.of(), List.of(), List.of());
        }
        var totals = snapshot.traceCount != null
            ? List.of(new ProjectQueryService.StatRow(null, null, snapshot.totalTokens, snapshot.totalCostUsd, snapshot.traceCount))
            : List.<ProjectQueryService.StatRow>of();
        return new ProjectQueryService.ProjectStats(totals, toRows(snapshot.byAgent), toRows(snapshot.bySubject));
    }

    public ZonedDateTime computedAt(String projectId) {
        var project = statsCollection.get(projectId).orElse(null);
        return project != null && project.stats != null ? project.stats.computedAt : null;
    }

    private List<ProjectQueryService.StatRow> toRows(List<ProjectStatsItem> items) {
        if (items == null) return List.of();
        return items.stream()
            .map(item -> new ProjectQueryService.StatRow(item.groupId, item.name, item.totalTokens, item.totalCostUsd, item.traceCount))
            .toList();
    }

    // full recomputation of the project snapshot (members' traces + per-subject breakdown)
    public void refresh(String projectId) {
        var scope = ProjectScope.resolve(projectCollection, projectId);
        if (scope == null) return;
        var data = new ProjectStatsData();
        var byAgent = aggregateGroup(statsFilter(scope, null), "$agent_id", "$agent_name");
        data.totalTokens = sumTokens(byAgent);
        data.totalCostUsd = sumCost(byAgent);
        data.traceCount = byAgent.stream().mapToLong(ProjectQueryService.StatRow::count).sum();
        data.byAgent = toItems(byAgent);
        var subjectIds = subjectIds(scope.projectId);
        data.bySubject = new ArrayList<>(subjectIds.size());
        data.subjects = new ArrayList<>(subjectIds.size());
        for (var subjectId : subjectIds) {
            var rows = aggregateGroup(statsFilter(scope, subjectId), "$agent_id", "$agent_name");
            var subjectStats = new ProjectSubjectStats();
            subjectStats.subjectId = subjectId;
            subjectStats.totalTokens = sumTokens(rows);
            subjectStats.totalCostUsd = sumCost(rows);
            subjectStats.traceCount = rows.stream().mapToLong(ProjectQueryService.StatRow::count).sum();
            subjectStats.byAgent = toItems(rows);
            data.subjects.add(subjectStats);
            if (subjectStats.traceCount != null && subjectStats.traceCount > 0) {
                data.bySubject.add(new ProjectQueryService.StatRow(subjectId, null,
                    subjectStats.totalTokens, subjectStats.totalCostUsd, subjectStats.traceCount).toItem());
            }
        }
        data.computedAt = ZonedDateTime.now();
        var now = data.computedAt;
        var entity = new ProjectStatsEntity();
        entity.id = projectId;
        entity.stats = data;
        statsCollection.replace(entity);   // upsert by _id (project id)
        projectCollection.update(Filters.eq("_id", projectId), Updates.combine(
            Updates.set("stats_dirty", Boolean.FALSE),
            Updates.set("last_stats_at", now),
            Updates.set("updated_at", now)));
    }

    private List<ProjectQueryService.StatRow> aggregateGroup(Bson match, String groupField, String nameField) {
        var aggregate = new Aggregate<Document>();
        aggregate.resultClass = Document.class;
        var accumulators = new ArrayList<BsonField>();
        accumulators.add(Accumulators.sum("total_tokens", "$total_tokens"));
        accumulators.add(Accumulators.sum("cost_usd", "$cost_usd"));
        accumulators.add(Accumulators.sum("count", 1L));
        if (nameField != null) {
            accumulators.add(Accumulators.first("name", nameField));
        }
        aggregate.pipeline = List.of(
            Aggregates.match(match),
            Aggregates.group(groupField, accumulators));
        var rows = new ArrayList<ProjectQueryService.StatRow>();
        for (var doc : traceCollection.aggregate(aggregate)) {
            var group = doc.get("_id");
            rows.add(new ProjectQueryService.StatRow(group instanceof String s ? s : null,
                doc.getString("name"), doc.getLong("total_tokens"), doc.getDouble("cost_usd"), doc.getLong("count")));
        }
        return rows;
    }

    private Bson statsFilter(ProjectScope scope, String subjectId) {
        var filters = new ArrayList<Bson>();
        filters.add(Filters.in("agent_id", scope.agentIds));
        if (subjectId != null) {
            var ids = attributionTargets(subjectId, "session");
            var runTraceIds = attributionRunTraceIds(subjectId);
            var subjectFilters = new ArrayList<Bson>();
            if (!ids.isEmpty()) subjectFilters.add(Filters.in("session_id", ids));
            if (!runTraceIds.isEmpty()) subjectFilters.add(Filters.in("trace_id", runTraceIds));
            if (subjectFilters.isEmpty()) filters.add(Filters.eq("_id", "__none__"));
            else filters.add(Filters.or(subjectFilters));
        }
        return Filters.and(filters);
    }

    private List<ProjectStatsItem> toItems(List<ProjectQueryService.StatRow> rows) {
        return rows.stream().map(ProjectQueryService.StatRow::toItem).toList();
    }

    private Long sumTokens(List<ProjectQueryService.StatRow> rows) {
        long sum = 0;
        for (var row : rows) sum += row.tokens() != null ? row.tokens() : 0;
        return sum;
    }

    private Double sumCost(List<ProjectQueryService.StatRow> rows) {
        double sum = 0;
        for (var row : rows) sum += row.costUsd() != null ? row.costUsd() : 0;
        return sum;
    }

    private List<String> subjectIds(String projectId) {
        var query = new Query();
        query.filter = Filters.eq("project_id", projectId);
        return subjectCollection.find(query).stream().map(s -> s.id).toList();
    }

    private List<String> attributionTargets(String subjectId, String targetType) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("subject_id", subjectId), Filters.eq("target_type", targetType));
        return attributionCollection.find(query).stream().map(a -> a.targetId).toList();
    }

    private List<String> attributionRunTraceIds(String subjectId) {
        var runIds = attributionTargets(subjectId, "run");
        if (runIds.isEmpty()) return List.of();
        var query = new Query();
        query.filter = Filters.in("_id", runIds);
        return agentRunCollection.find(query).stream().map(r -> r.traceId).filter(id -> id != null).toList();
    }
}
