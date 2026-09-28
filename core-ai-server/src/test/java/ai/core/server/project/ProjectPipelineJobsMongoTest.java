package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.workflow.WorkflowTestModule;
import com.mongodb.client.model.Indexes;
import core.framework.inject.Inject;
import core.framework.mongo.Mongo;
import core.framework.mongo.MongoCollection;
import core.framework.scheduler.JobContext;
import core.framework.test.Context;
import core.framework.test.IntegrationExtension;
import org.bson.Document;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;
import org.junit.jupiter.api.extension.ExtendWith;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.time.ZonedDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The pipeline drivers' scan filters against a real Mongo: core-ng persists an unset cursor as an
 * explicit null, so a field-absence-only check excludes a project that has never been analyzed —
 * its first attribution pass (and with it the first proposals, or the first subject analysis)
 * would never run. A mocked collection cannot see this: the filter has to meet real stored data.
 *
 * @author stephen
 */
@EnabledIf("mongoReachable")
@ExtendWith(IntegrationExtension.class)
@Context(module = WorkflowTestModule.class)
class ProjectPipelineJobsMongoTest {
    private static final String PROJECTS = "projects";
    private static final String RUN = Long.toHexString(System.nanoTime());
    private static final String NEVER_ANALYZED = "job-cursor-never-" + RUN;
    private static final String RECENTLY_ANALYZED = "job-cursor-recent-" + RUN;
    private static final String NEVER_SUBJECT_ANALYZED = "job-cursor-never-subject-" + RUN;
    private static final String SUBJECT = "job-cursor-subject-" + RUN;
    private static final String ATTRIBUTION = "job-cursor-attribution-" + RUN;
    private static final List<String> PROJECT_IDS = List.of(NEVER_ANALYZED, RECENTLY_ANALYZED, NEVER_SUBJECT_ANALYZED);

    static boolean mongoReachable() {
        try (var socket = new Socket()) {
            socket.connect(new InetSocketAddress("localhost", 27017), 1000);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    @Inject
    Mongo mongo;
    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;

    // wftest is not migrated: mirror the production scan indexes or notablescan reports the missing
    // index instead of the scan being wrong
    @BeforeEach
    void ensureScanIndexes() {
        mongo.createIndex(PROJECTS, Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("last_analyzed_at")));
        mongo.createIndex(PROJECTS, Indexes.compoundIndex(Indexes.ascending("status"), Indexes.ascending("last_analysis_at")));
        mongo.createIndex("project_subjects", Indexes.ascending("project_id"));
        mongo.createIndex("project_subject_attributions", Indexes.ascending("subject_id"));
    }

    @AfterEach
    void removeFixtures() {
        for (var id : PROJECT_IDS) projectCollection.delete(id);
        subjectCollection.delete(SUBJECT);
        attributionCollection.delete(ATTRIBUTION);
    }

    @Test
    void attributionScanMatchesProjectWithUninitializedCursor() {
        projectCollection.insert(project(NEVER_ANALYZED, null, null));
        var stored = document(NEVER_ANALYZED);
        assertTrue(stored.containsKey("last_analyzed_at"), "the unset cursor must be stored as an explicit null");
        assertNull(stored.get("last_analyzed_at"));

        var service = mock(ProjectAnalysisService.class);
        when(service.claimAnalysis(anyString())).thenReturn(Boolean.TRUE);

        attributionJob(service).execute(context());

        verify(service).claimAnalysis(NEVER_ANALYZED);
        verify(service).runAttribution(NEVER_ANALYZED);
    }

    @Test
    void attributionScanLeavesRecentlyAnalyzedProjectsAlone() {
        projectCollection.insert(project(RECENTLY_ANALYZED, ZonedDateTime.now(), null));

        var service = mock(ProjectAnalysisService.class);
        when(service.claimAnalysis(anyString())).thenReturn(Boolean.TRUE);

        attributionJob(service).execute(context());

        verify(service, never()).claimAnalysis(RECENTLY_ANALYZED);
        verify(service, never()).runAttribution(RECENTLY_ANALYZED);
    }

    @Test
    void subjectAnalysisScanMatchesProjectWithUninitializedCursorAndUnconsumedMaterial() {
        projectCollection.insert(project(NEVER_SUBJECT_ANALYZED, ZonedDateTime.now(), null));
        subjectCollection.insert(subject());
        attributionCollection.insert(attribution());

        var service = mock(ProjectAnalysisService.class);
        when(service.claimAnalysis(anyString())).thenReturn(Boolean.TRUE);

        analysisJob(service).execute(context());

        verify(service).claimAnalysis(NEVER_SUBJECT_ANALYZED);
        verify(service).runAnalysis(NEVER_SUBJECT_ANALYZED, null);
    }

    private Project project(String id, ZonedDateTime lastAnalyzedAt, ZonedDateTime lastAnalysisAt) {
        var project = new Project();
        project.id = id;
        project.userId = "job-cursor-user-" + RUN;
        project.name = "job cursor " + id;
        project.status = ProjectService.STATUS_ACTIVE;
        project.lastAnalyzedAt = lastAnalyzedAt;
        project.lastAnalysisAt = lastAnalysisAt;
        project.createdAt = ZonedDateTime.now();
        project.updatedAt = project.createdAt;
        return project;
    }

    private ProjectSubject subject() {
        var subject = new ProjectSubject();
        subject.id = SUBJECT;
        subject.projectId = NEVER_SUBJECT_ANALYZED;
        subject.userId = "job-cursor-user-" + RUN;
        subject.name = "job cursor subject";
        subject.status = ProjectService.SUBJECT_STARTED;
        subject.createdAt = ZonedDateTime.now();
        subject.updatedAt = subject.createdAt;
        return subject;
    }

    private ProjectSubjectAttribution attribution() {
        var attribution = new ProjectSubjectAttribution();
        attribution.id = ATTRIBUTION;
        attribution.projectId = NEVER_SUBJECT_ANALYZED;
        attribution.subjectId = SUBJECT;
        attribution.targetType = "run";
        attribution.targetId = "job-cursor-run-" + RUN;
        attribution.createdAt = ZonedDateTime.now();
        return attribution;
    }

    private ProjectAttributionJob attributionJob(ProjectAnalysisService service) {
        var job = new ProjectAttributionJob();
        job.projectCollection = projectCollection;
        job.subjectCollection = subjectCollection;
        job.attributionCollection = attributionCollection;
        job.analysisService = service;
        return job;
    }

    private ProjectAnalysisJob analysisJob(ProjectAnalysisService service) {
        var job = new ProjectAnalysisJob();
        job.projectCollection = projectCollection;
        job.subjectCollection = subjectCollection;
        job.attributionCollection = attributionCollection;
        job.analysisService = service;
        return job;
    }

    private JobContext context() {
        return new JobContext("project-attribution", ZonedDateTime.now());
    }

    private Document document(String id) {
        var result = mongo.runCommand(new Document("find", PROJECTS)
            .append("filter", new Document("_id", id))
            .append("limit", 1));
        var batch = ((Document) result.get("cursor")).getList("firstBatch", Document.class);
        return batch.isEmpty() ? null : batch.getFirst();
    }
}
