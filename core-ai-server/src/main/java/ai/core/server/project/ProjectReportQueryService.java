package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Project;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Projections;
import com.mongodb.client.model.Sorts;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Report directory of a project. Project reports = artifacts of member sessions/runs (candidate pool, subject
 * possibly still unassigned) plus files explicitly attributed to one of the project's subjects (uploads,
 * cascaded/inherited artifacts, material of former members). A file's subject comes from the file attribution
 * row ONLY, never from the agent scope, so one report shows up under exactly one subject per project.
 *
 * @author stephen
 */
public class ProjectReportQueryService {
    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_UPLOAD = "upload";
    private static final Bson FILE_META_PROJECTION = Projections.exclude("data", "storage_path");

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ChatSession> chatSessionCollection;
    @Inject
    MongoCollection<AgentRun> agentRunCollection;
    @Inject
    MongoCollection<AgentDefinition> agentCollection;
    @Inject
    MongoCollection<FileRecord> fileRecordCollection;
    @Inject
    ProjectAttributionStore attributionStore;

    public List<ProjectReport> reports(String projectId, String subjectId, String agentId) {
        return reports(projectId, new ReportFilter(subjectId, agentId, null, null, null));
    }

    public List<ProjectReport> reports(String projectId, ReportFilter filter) {
        if (projectCollection.get(projectId).isEmpty()) return List.of();
        var subjectByFile = attributionStore.fileSubjects(projectId);
        var byFile = new LinkedHashMap<String, ProjectReport>();
        var scope = ProjectScope.resolve(projectCollection, projectId);
        if (scope != null) {
            addRunReports(byFile, scope, filter.agentId());
            addSessionReports(byFile, scope, filter.agentId());
        }
        var records = fileRecords(subjectByFile.keySet(), byFile.keySet());
        if (filter.agentId() == null || filter.agentId().isBlank()) {
            for (var fileId : subjectByFile.keySet()) {
                if (byFile.containsKey(fileId)) continue;
                var record = records.get(fileId);
                if (record == null) continue;   // attributed file has been deleted
                byFile.put(fileId, new ProjectReport(fileId, record.fileName, record.contentType, record.size, record.createdAt, null, null, null, SOURCE_UPLOAD, null));
            }
        }
        var names = agentNames(byFile.values().stream().map(ProjectReport::agentId).filter(id -> id != null && !id.isBlank()).distinct().toList());
        var result = new ArrayList<ProjectReport>();
        for (var r : byFile.values()) {
            var subject = subjectByFile.get(r.fileId());
            if (!filter.accepts(subject, r.createdAt())) continue;
            var record = records.get(r.fileId());
            result.add(new ProjectReport(r.fileId(), r.fileName(), r.contentType(), r.size(), r.createdAt(), subject, r.agentId(), names.get(r.agentId()),
                r.source(), record != null ? record.shareToken : null));
        }
        result.sort((a, b) -> compareDesc(a.createdAt(), b.createdAt()));
        return result;
    }

    // one metadata fetch (no payload) for every candidate file: share tokens plus the uploads' names/sizes
    private Map<String, FileRecord> fileRecords(Set<String> attributed, Set<String> produced) {
        var ids = new LinkedHashSet<String>(attributed);
        ids.addAll(produced);
        var result = new HashMap<String, FileRecord>();
        if (ids.isEmpty()) return result;
        var query = new Query();
        query.filter = Filters.in("_id", ids);
        query.projection = FILE_META_PROJECTION;
        for (var record : fileRecordCollection.find(query)) result.put(record.id, record);
        return result;
    }

    private Map<String, String> agentNames(List<String> agentIds) {
        var names = new HashMap<String, String>();
        if (agentIds.isEmpty()) return names;
        agentCollection.find(Filters.in("_id", agentIds)).forEach(a -> names.put(a.id, a.name));
        return names;
    }

    private void addSessionReports(Map<String, ProjectReport> byFile, ProjectScope scope, String agentId) {
        var query = new Query();
        query.filter = Filters.and(Filters.in("agent_id", scope.agentIds), Filters.or(Filters.exists("deleted_at", false), Filters.eq("deleted_at", null)));
        query.sort = Sorts.descending("last_message_at");
        for (var session : chatSessionCollection.find(query)) {
            if (session.artifacts == null || !matchesAgent(session.agentId, agentId)) continue;
            for (var artifact : session.artifacts) {
                if (artifact.fileId == null) continue;
                var at = artifact.createdAt != null ? artifact.createdAt : session.lastMessageAt;
                byFile.putIfAbsent(artifact.fileId, new ProjectReport(artifact.fileId, artifact.fileName, artifact.contentType, artifact.size, at, null, session.agentId, null, SOURCE_AGENT, null));
            }
        }
    }

    private void addRunReports(Map<String, ProjectReport> byFile, ProjectScope scope, String agentId) {
        var query = new Query();
        query.filter = Filters.in("agent_id", scope.agentIds);
        query.sort = Sorts.descending("started_at");
        for (var run : agentRunCollection.find(query)) {
            if (run.artifacts == null || !matchesAgent(run.agentId, agentId)) continue;
            for (var artifact : run.artifacts) {
                if (artifact.fileId == null) continue;
                var at = artifact.createdAt != null ? artifact.createdAt : run.startedAt;
                byFile.putIfAbsent(artifact.fileId, new ProjectReport(artifact.fileId, artifact.fileName, artifact.contentType, artifact.size, at, null, run.agentId, null, SOURCE_AGENT, null));
            }
        }
    }

    private boolean matchesAgent(String actualAgentId, String filterAgentId) {
        return filterAgentId == null || filterAgentId.isBlank() || filterAgentId.equals(actualAgentId);
    }

    private int compareDesc(ZonedDateTime left, ZonedDateTime right) {
        if (left == null && right == null) return 0;
        if (left == null) return 1;
        if (right == null) return -1;
        return right.compareTo(left);
    }

    public record ProjectReport(String fileId, String fileName, String contentType, Long size,
                                ZonedDateTime createdAt, String subjectId, String agentId, String agentName,
                                String source, String shareToken) {
    }

    /** subjectId filters to one subject; unassigned=true keeps only reports without a subject; from/to bound createdAt */
    public record ReportFilter(String subjectId, String agentId, ZonedDateTime from, ZonedDateTime to, Boolean unassigned) {
        boolean accepts(String subject, ZonedDateTime createdAt) {
            if (subjectId != null && !subjectId.isBlank() && !subjectId.equals(subject)) return false;
            if (subject != null && Boolean.TRUE.equals(unassigned)) return false;
            if (from != null && (createdAt == null || createdAt.isBefore(from))) return false;
            return to == null || createdAt != null && !createdAt.isAfter(to);
        }
    }
}
