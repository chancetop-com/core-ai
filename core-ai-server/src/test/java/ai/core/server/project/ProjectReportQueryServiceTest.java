package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectMemberRef;
import ai.core.server.domain.ProjectSubjectAttribution;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class ProjectReportQueryServiceTest {
    private static ProjectSubjectAttribution fileRow(String fileId, String subjectId, String agentId, String source, ZonedDateTime at) {
        var row = new ProjectSubjectAttribution();
        row.projectId = "p-1";
        row.subjectId = subjectId;
        row.targetType = "file";
        row.targetId = fileId;
        row.agentId = agentId;
        row.source = source;
        row.targetCreatedAt = at;
        return row;
    }

    private static FileRecord file(String id, String name) {
        var record = new FileRecord();
        record.id = id;
        record.fileName = name;
        record.contentType = "text/html";
        record.size = 10L;
        record.shareToken = "tok-" + id;
        record.createdAt = ZonedDateTime.parse("2026-01-01T00:00:00Z");
        return record;
    }

    private static AgentRunArtifact artifact(String fileId, ZonedDateTime at) {
        var artifact = new AgentRunArtifact();
        artifact.fileId = fileId;
        artifact.fileName = fileId + ".html";
        artifact.createdAt = at;
        return artifact;
    }

    private ProjectReportQueryService service;
    private ProjectAttributionStore store;
    private MongoCollection<FileRecord> files;
    private MongoCollection<AgentRun> runs;
    private MongoCollection<ChatSession> sessions;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ProjectReportQueryService();
        store = mock(ProjectAttributionStore.class);
        files = (MongoCollection<FileRecord>) mock(MongoCollection.class);
        runs = (MongoCollection<AgentRun>) mock(MongoCollection.class);
        sessions = (MongoCollection<ChatSession>) mock(MongoCollection.class);
        var projects = (MongoCollection<Project>) mock(MongoCollection.class);
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        service.attributionStore = store;
        service.fileRecordCollection = files;
        service.agentRunCollection = runs;
        service.chatSessionCollection = sessions;
        service.projectCollection = projects;
        service.agentCollection = agents;

        var project = new Project();
        project.id = "p-1";
        var member = new ProjectMemberRef();
        member.type = "agent";
        member.id = "agent-1";
        project.members = List.of(member);
        when(projects.get("p-1")).thenReturn(Optional.of(project));
        when(agents.find(any(Bson.class))).thenReturn(List.of());
        when(runs.find(any(Query.class))).thenReturn(List.of());
        when(sessions.find(any(Query.class))).thenReturn(List.of());
        when(files.find(any(Query.class))).thenReturn(List.of());
        when(store.fileSubjects("p-1")).thenReturn(Map.of());
        when(store.filedFiles(anyString(), any(), any(), any(), anyInt(), anyInt())).thenReturn(List.of());
        when(store.filedFileCount(anyString(), any(), any(), any())).thenReturn(0L);
    }

    @Test
    void filedPathPagesOnAttributionTableAndNeverScansMembers() {
        var at = ZonedDateTime.parse("2026-09-01T10:00:00Z");
        when(store.filedFiles("p-1", "s-1", null, null, 50, 25)).thenReturn(List.of(fileRow("f-1", "s-1", "agent-1", "inherited", at)));
        when(store.filedFileCount("p-1", "s-1", null, null)).thenReturn(120L);
        when(files.find(any(Query.class))).thenReturn(List.of(file("f-1", "daily.html")));

        var page = service.reports("p-1", new ProjectReportQueryService.ReportFilter("s-1", null, null, null, null), 50, 25);

        assertEquals(120L, page.total());
        assertEquals(1, page.reports().size());
        var report = page.reports().getFirst();
        assertEquals("daily.html", report.fileName());
        assertEquals("s-1", report.subjectId());
        assertEquals(at, report.createdAt());
        assertEquals("tok-f-1", report.shareToken());
        assertEquals(ProjectReportQueryService.SOURCE_AGENT, report.source());
        verify(runs, never()).find(any(Query.class));
        verify(sessions, never()).find(any(Query.class));
    }

    @Test
    void uploadedRowsReportUploadSourceAndDeletedFilesAreDropped() {
        when(store.filedFiles("p-1", null, null, null, 0, 50)).thenReturn(List.of(
            fileRow("f-up", "s-1", null, "upload", ZonedDateTime.parse("2026-09-02T00:00:00Z")),
            fileRow("f-gone", "s-1", null, "cascade", ZonedDateTime.parse("2026-09-01T00:00:00Z"))));
        when(store.filedFileCount("p-1", null, null, null)).thenReturn(2L);
        when(files.find(any(Query.class))).thenReturn(List.of(file("f-up", "manual.pdf")));

        var page = service.reports("p-1", new ProjectReportQueryService.ReportFilter(null, null, null, null, Boolean.FALSE), 0, 50);

        assertEquals(1, page.reports().size());
        assertEquals(ProjectReportQueryService.SOURCE_UPLOAD, page.reports().getFirst().source());
        assertNull(page.reports().getFirst().agentId());
    }

    @Test
    void inboxExcludesHomedFilesAndPagesInMemory() {
        var run = new AgentRun();
        run.id = "run-1";
        run.agentId = "agent-1";
        run.artifacts = List.of(
            artifact("f-old", ZonedDateTime.parse("2026-08-01T00:00:00Z")),
            artifact("f-homed", ZonedDateTime.parse("2026-08-02T00:00:00Z")),
            artifact("f-new", ZonedDateTime.parse("2026-08-03T00:00:00Z")));
        when(runs.find(any(Query.class))).thenReturn(List.of(run));
        when(store.fileSubjects("p-1")).thenReturn(Map.of("f-homed", "s-1"));

        var firstPage = service.reports("p-1", new ProjectReportQueryService.ReportFilter(null, null, null, null, Boolean.TRUE), 0, 1);
        var secondPage = service.reports("p-1", new ProjectReportQueryService.ReportFilter(null, null, null, null, Boolean.TRUE), 1, 1);

        assertEquals(2L, firstPage.total());
        assertEquals("f-new", firstPage.reports().getFirst().fileId());   // newest first
        assertEquals("f-old", secondPage.reports().getFirst().fileId());
        assertNull(firstPage.reports().getFirst().subjectId());
        // the member scan is bounded: newest artifact-bearing records only
        var query = ArgumentCaptor.forClass(Query.class);
        verify(runs, org.mockito.Mockito.atLeastOnce()).find(query.capture());
        assertEquals(ProjectReportQueryService.MAX_SOURCE_RECORDS, query.getValue().limit);
    }

    @Test
    void statsCombineInboxSizeWithPerSubjectCounts() {
        var run = new AgentRun();
        run.id = "run-1";
        run.agentId = "agent-1";
        run.artifacts = List.of(artifact("f-1", ZonedDateTime.parse("2026-08-01T00:00:00Z")), artifact("f-2", ZonedDateTime.parse("2026-08-02T00:00:00Z")));
        when(runs.find(any(Query.class))).thenReturn(List.of(run));
        when(store.fileSubjects("p-1")).thenReturn(Map.of("f-1", "s-1"));
        var latest = ZonedDateTime.parse("2026-08-01T00:00:00Z");
        when(store.fileStatsBySubject("p-1")).thenReturn(Map.of("s-1", new ProjectAttributionStore.SubjectFileStats(1, latest)));

        var stats = service.stats("p-1");

        assertEquals(1L, stats.unassigned());
        assertEquals(1L, stats.bySubject().get("s-1").count());
        assertEquals(latest, stats.bySubject().get("s-1").latest());
    }

    @Test
    void limitIsClampedAndUnknownProjectIsEmpty() {
        service.reports("p-1", new ProjectReportQueryService.ReportFilter("s-1", null, null, null, null), -5, 10_000);
        verify(store).filedFiles(eq("p-1"), eq("s-1"), any(), any(), eq(0), eq(ProjectReportQueryService.MAX_LIMIT));

        var page = service.reports("missing", new ProjectReportQueryService.ReportFilter(null, null, null, null, null), 0, 10);
        assertEquals(0L, page.total());
    }
}
