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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Report directory of a project, paged. Two sources:
 * <ul>
 *   <li><b>filed</b> — files attributed to a subject. Served from the attribution table alone (sorted and paged
 *       on the denormalized file time), then one metadata fetch for the page. Unbounded history is fine here.</li>
 *   <li><b>inbox</b> — artifacts of member sessions/runs that have no home yet. Bounded by construction: only
 *       the newest {@link #MAX_SOURCE_RECORDS} artifact-bearing sessions and runs are scanned; older unfiled
 *       material is reached through the attributor / analysis, not through this list.</li>
 * </ul>
 * A file's subject comes from the file attribution row ONLY, never from the agent scope, so one report shows
 * up under exactly one subject per project.
 *
 * @author stephen
 */
public class ProjectReportQueryService {
    public static final String SOURCE_AGENT = "agent";
    public static final String SOURCE_UPLOAD = "upload";
    static final int MAX_SOURCE_RECORDS = 1000;   // newest artifact-bearing sessions/runs considered per type
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_LIMIT = 200;
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

    /** newest filed reports of the project / one subject (timeline and other bounded consumers) */
    public List<ProjectReport> reports(String projectId, String subjectId, int limit) {
        return reports(projectId, new ReportFilter(subjectId, null, null, null, Boolean.FALSE), 0, limit).reports();
    }

    public ReportPage reports(String projectId, ReportFilter filter, int offset, int limit) {
        if (projectCollection.get(projectId).isEmpty()) return new ReportPage(List.of(), 0);
        int safeOffset = Math.max(offset, 0);
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        return Boolean.TRUE.equals(filter.unassigned()) ? inbox(projectId, filter, safeOffset, safeLimit) : filed(projectId, filter, safeOffset, safeLimit);
    }

    /** per-subject counts / newest report plus the (bounded) size of the inbox: what the project page cards show */
    public ReportStats stats(String projectId) {
        if (projectCollection.get(projectId).isEmpty()) return new ReportStats(0, Map.of());
        var unassigned = inbox(projectId, new ReportFilter(null, null, null, null, Boolean.TRUE), 0, 1).total();
        return new ReportStats(unassigned, attributionStore.fileStatsBySubject(projectId));
    }

    // ---- filed: attribution table is the source of truth, paged in Mongo ----

    private ReportPage filed(String projectId, ReportFilter filter, int offset, int limit) {
        var rows = attributionStore.filedFiles(projectId, filter.subjectId(), filter.from(), filter.to(), offset, limit);
        var total = attributionStore.filedFileCount(projectId, filter.subjectId(), filter.from(), filter.to());
        var agentFilter = filter.agentId();
        if (agentFilter != null && !agentFilter.isBlank()) {
            // agent filter is rare on the filed path: applied on the page (agent_id is denormalized on the row)
            rows = rows.stream().filter(r -> agentFilter.equals(r.agentId)).toList();
        }
        var records = fileRecords(rows.stream().map(r -> r.targetId).toList());
        var names = agentNames(rows.stream().map(r -> r.agentId).filter(id -> id != null && !id.isBlank()).distinct().toList());
        var result = new ArrayList<ProjectReport>();
        for (var row : rows) {
            var record = records.get(row.targetId);
            if (record == null) continue;   // attributed file has been deleted
            var source = ProjectAttributionStore.SOURCE_UPLOAD.equals(row.source) ? SOURCE_UPLOAD : SOURCE_AGENT;
            var createdAt = row.targetCreatedAt != null ? row.targetCreatedAt : record.createdAt;
            result.add(new ProjectReport(record.id, record.fileName, record.contentType, record.size, createdAt, row.subjectId,
                row.agentId, names.get(row.agentId), source, record.shareToken));
        }
        return new ReportPage(result, total);
    }

    // ---- inbox: newest member artifacts minus the ones already homed, paged in memory (bounded scan) ----

    private ReportPage inbox(String projectId, ReportFilter filter, int offset, int limit) {
        var scope = ProjectScope.resolve(projectCollection, projectId);
        if (scope == null) return new ReportPage(List.of(), 0);
        var homed = attributionStore.fileSubjects(projectId);
        var byFile = new LinkedHashMap<String, ProjectReport>();
        addRunReports(byFile, scope, filter.agentId());
        addSessionReports(byFile, scope, filter.agentId());
        var candidates = new ArrayList<ProjectReport>();
        for (var r : byFile.values()) {
            if (homed.containsKey(r.fileId())) continue;
            if (!filter.accepts(r.createdAt())) continue;
            candidates.add(r);
        }
        candidates.sort((a, b) -> compareDesc(a.createdAt(), b.createdAt()));
        var total = candidates.size();
        if (offset >= total) return new ReportPage(List.of(), total);
        var page = candidates.subList(offset, Math.min(offset + limit, total));
        var records = fileRecords(page.stream().map(ProjectReport::fileId).toList());
        var names = agentNames(page.stream().map(ProjectReport::agentId).filter(id -> id != null && !id.isBlank()).distinct().toList());
        var result = new ArrayList<ProjectReport>();
        for (var r : page) {
            var record = records.get(r.fileId());
            result.add(new ProjectReport(r.fileId(), r.fileName(), r.contentType(), r.size(), r.createdAt(), null, r.agentId(), names.get(r.agentId()),
                SOURCE_AGENT, record != null ? record.shareToken : null));
        }
        return new ReportPage(result, total);
    }

    // one metadata fetch (no payload) for the files of the current page
    private Map<String, FileRecord> fileRecords(Collection<String> ids) {
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

    // only records that actually carry artifacts, newest first, bounded: the (agent_id, time) index
    // walks the member history in order and the artifacts filter drops the artifact-less bulk
    private void addSessionReports(Map<String, ProjectReport> byFile, ProjectScope scope, String agentId) {
        var query = new Query();
        query.filter = Filters.and(Filters.in("agent_id", scope.agentIds), Filters.exists("artifacts.0", true),
            Filters.or(Filters.exists("deleted_at", false), Filters.eq("deleted_at", null)));
        query.sort = Sorts.descending("last_message_at");
        query.limit = MAX_SOURCE_RECORDS;
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
        query.filter = Filters.and(Filters.in("agent_id", scope.agentIds), Filters.exists("artifacts.0", true));
        query.sort = Sorts.descending("started_at");
        query.limit = MAX_SOURCE_RECORDS;
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

    public record ReportPage(List<ProjectReport> reports, long total) {
    }

    public record ReportStats(long unassigned, Map<String, ProjectAttributionStore.SubjectFileStats> bySubject) {
    }

    /** subjectId filters to one subject; unassigned=true switches to the inbox; from/to bound the report time */
    public record ReportFilter(String subjectId, String agentId, ZonedDateTime from, ZonedDateTime to, Boolean unassigned) {
        boolean accepts(ZonedDateTime createdAt) {
            if (from != null && (createdAt == null || createdAt.isBefore(from))) return false;
            return to == null || createdAt != null && !createdAt.isAfter(to);
        }
    }
}
