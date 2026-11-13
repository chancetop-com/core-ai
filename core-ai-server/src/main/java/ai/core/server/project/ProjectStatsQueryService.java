package ai.core.server.project;

import ai.core.server.run.AgentRunQuery;

import ai.core.server.run.AgentRunStore;

import ai.core.server.domain.ProjectStatsData;
import ai.core.server.domain.ProjectStatsEntity;
import ai.core.server.domain.ProjectStatsItem;
import ai.core.server.domain.ProjectSubjectStats;
import ai.core.server.trace.TraceAggregation;
import ai.core.server.trace.TraceQuery;
import ai.core.server.trace.TraceStore;
import core.framework.inject.Inject;

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
    ProjectStore projectStore;
    @Inject
    ProjectStatsStore statsStore;
    @Inject
    ProjectSubjectStore subjectStore;
    @Inject
    AgentRunStore agentRunStore;
    @Inject
    ProjectAttributionRepository attributionRepository;
    @Inject
    TraceStore traceStore;

    // reads the cached snapshot; subjectId != null returns that subject's breakdown from the cache
    public ProjectQueryService.ProjectStats stats(String projectId, String subjectId) {
        var project = statsStore.get(projectId).orElse(null);
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
        var project = statsStore.get(projectId).orElse(null);
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
        var scope = ProjectScope.resolve(projectStore, projectId);
        if (scope == null) return;
        var data = new ProjectStatsData();
        var byAgent = aggregateGroup(statsFilter(scope, null));
        data.totalTokens = sumTokens(byAgent);
        data.totalCostUsd = sumCost(byAgent);
        data.traceCount = byAgent.stream().mapToLong(ProjectQueryService.StatRow::count).sum();
        data.byAgent = toItems(byAgent);
        var subjectIds = subjectIds(scope.projectId);
        data.bySubject = new ArrayList<>(subjectIds.size());
        data.subjects = new ArrayList<>(subjectIds.size());
        for (var subjectId : subjectIds) {
            var rows = aggregateGroup(statsFilter(scope, subjectId));
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
        statsStore.replace(entity);   // upsert by project id
        projectStore.update(projectId, project -> {
            project.statsDirty = Boolean.FALSE;
            project.lastStatsAt = now;
            project.updatedAt = now;
        });
    }

    private List<ProjectQueryService.StatRow> aggregateGroup(TraceQuery query) {
        var aggregation = new TraceAggregation();
        aggregation.query = query;
        aggregation.group = TraceAggregation.Group.AGENT_ID;
        aggregation.firstField = TraceAggregation.FirstField.AGENT_NAME;
        var rows = new ArrayList<ProjectQueryService.StatRow>();
        for (var row : traceStore.aggregate(aggregation)) {
            rows.add(new ProjectQueryService.StatRow(row.group, row.firstValue, row.totalTokens, row.costUsd, row.count));
        }
        return rows;
    }

    private TraceQuery statsFilter(ProjectScope scope, String subjectId) {
        var query = new TraceQuery();
        query.agentIds = scope.agentIds;
        if (subjectId != null) {
            var ids = attributionTargets(subjectId, "session");
            var runTraceIds = attributionRunTraceIds(subjectId);
            if (ids.isEmpty() && runTraceIds.isEmpty()) {
                query.matchNothing = true;
            } else {
                query.anySessionIds = ids.isEmpty() ? null : ids;
                query.anyTraceIds = runTraceIds.isEmpty() ? null : runTraceIds;
            }
        }
        return query;
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
        return subjectStore.listByProject(projectId).stream().map(s -> s.id).toList();
    }

    private List<String> attributionTargets(String subjectId, String targetType) {
        return attributionRepository.listBySubjectAndType(subjectId, targetType).stream().map(a -> a.targetId).toList();
    }

    private List<String> attributionRunTraceIds(String subjectId) {
        var runIds = attributionTargets(subjectId, "run");
        if (runIds.isEmpty()) return List.of();
        var query = new AgentRunQuery();
        query.ids = runIds;
        query.limit = runIds.size();
        return agentRunStore.find(query).stream().map(r -> r.traceId).filter(id -> id != null).toList();
    }
}



