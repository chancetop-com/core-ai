package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.scheduler.JobContext;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduling gate of the attribution job: a started subject is the classic driver, while a project
 * with subject auto-discovery on must run even before it tracks anything — that pass is what
 * proposes its first subjects (an empty project used to be skipped forever).
 *
 * @author stephen
 */
class ProjectAttributionJobTest {
    private static Project project(String autoSubjects) {
        var project = new Project();
        project.id = "p-1";
        project.autoSubjects = autoSubjects;
        return project;
    }

    private ProjectAttributionJob job;
    private MongoCollection<Project> projects;
    private MongoCollection<ProjectSubject> subjects;
    private ProjectAnalysisService analysisService;
    private JobContext context;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        job = new ProjectAttributionJob();
        projects = (MongoCollection<Project>) mock(MongoCollection.class);
        job.projectCollection = projects;
        subjects = (MongoCollection<ProjectSubject>) mock(MongoCollection.class);
        job.subjectCollection = subjects;
        job.attributionCollection = (MongoCollection<ProjectSubjectAttribution>) mock(MongoCollection.class);
        analysisService = mock(ProjectAnalysisService.class);
        job.analysisService = analysisService;
        when(analysisService.claimAnalysis(anyString())).thenReturn(Boolean.TRUE);
        context = new JobContext("project-attribution", ZonedDateTime.now());
    }

    @Test
    void runsWithoutStartedSubjectWhenAutoDiscoveryOn() {
        when(projects.find(any(Query.class))).thenReturn(List.of(project(null)));
        when(subjects.count(any(Bson.class))).thenReturn(0L);

        job.execute(context);

        verify(analysisService).claimAnalysis("p-1");
        verify(analysisService).runAttribution("p-1");
    }

    @Test
    void skipsWithoutStartedSubjectWhenAutoDiscoveryOff() {
        when(projects.find(any(Query.class))).thenReturn(List.of(project(ProjectService.AUTO_SUBJECTS_OFF)));
        when(subjects.count(any(Bson.class))).thenReturn(0L);

        job.execute(context);

        verify(analysisService, never()).claimAnalysis(anyString());
        verify(analysisService, never()).runAttribution(anyString());
    }

    @Test
    void runsWithStartedSubjectWhenAutoDiscoveryOff() {
        when(projects.find(any(Query.class))).thenReturn(List.of(project(ProjectService.AUTO_SUBJECTS_OFF)));
        when(subjects.count(any(Bson.class))).thenReturn(1L);

        job.execute(context);

        verify(analysisService).claimAnalysis("p-1");
        verify(analysisService).runAttribution("p-1");
    }
}
