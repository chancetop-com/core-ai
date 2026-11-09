package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Review operations of auto-discovered subjects: accept starts the proposal, reject deletes it and
 * remembers the name, merge re-homes its material onto a real subject.
 *
 * @author core-ai
 */
class ProjectSubjectReviewServiceTest {
    private static final ZonedDateTime T1 = ZonedDateTime.of(2026, 9, 1, 10, 0, 0, 0, ZoneId.of("UTC"));

    private ProjectSubjectReviewService service;
    private MongoCollection<Project> projects;
    private MongoCollection<ProjectSubject> subjects;
    private MongoCollection<ProjectSubjectAttribution> attributions;
    private ProjectService projectService;
    private ProjectStateService stateService;
    private ProjectAttributionStore attributionStore;
    private ProjectTargetScanStore scanStore;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ProjectSubjectReviewService();
        projects = (MongoCollection<Project>) mock(MongoCollection.class);
        service.projectStore = ProjectStoreTestSupport.store(projects);
        subjects = (MongoCollection<ProjectSubject>) mock(MongoCollection.class);
        service.subjectStore = MongoProjectSubjectStore.of(subjects);
        attributions = (MongoCollection<ProjectSubjectAttribution>) mock(MongoCollection.class);
        service.attributionRepository = MongoProjectAttributionRepository.of(attributions);
        projectService = mock(ProjectService.class);
        service.projectService = projectService;
        stateService = mock(ProjectStateService.class);
        service.stateService = stateService;
        attributionStore = mock(ProjectAttributionStore.class);
        service.attributionStore = attributionStore;
        scanStore = mock(ProjectTargetScanStore.class);
        service.scanStore = scanStore;

        var project = new Project();
        project.id = "p-1";
        project.createdAt = T1;
        project.updatedAt = T1;
        when(projects.get("p-1")).thenReturn(Optional.of(project));
        when(projectService.require("p-1")).thenReturn(project);
        var proposed = subject("s-1", ProjectService.SUBJECT_PROPOSED, "Acme Spa Inc");
        when(projectService.subject("p-1", "s-1")).thenReturn(proposed);
        when(subjects.get("s-1")).thenReturn(Optional.of(proposed));
        when(attributions.find(any(Bson.class))).thenReturn(List.of());
    }

    @Test
    void acceptStartsTheProposal() {
        service.acceptSubject("p-1", "user-1", false, "s-1");

        verify(stateService).recordSubjectStatus("p-1", "s-1", ProjectService.SUBJECT_STARTED, "user-1");
        verify(subjects).replace(any(ProjectSubject.class));
    }

    @Test
    void rejectDeletesSubjectAndRemembersTheNormalizedName() {
        service.rejectSubject("p-1", "user-1", false, "s-1");

        verify(attributions).delete(any(Bson.class));
        verify(stateService).deleteSubjectEvents("s-1");
        verify(subjects).delete(anyString());
        verify(projects).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void mergeRehomesMaterialAndKeepsTheNameAsAlias() {
        var into = subject("s-2", null, "Acme Spa");
        when(projectService.subject("p-1", "s-2")).thenReturn(into);
        when(subjects.get("s-2")).thenReturn(Optional.of(into));
        when(attributions.find(any(Bson.class))).thenReturn(List.of(row("a-1", "file", "f-1"), row("a-2", "session", "c-1")));

        service.mergeSubject("p-1", "user-1", false, "s-1", "s-2");

        verify(attributionStore).moveFile("p-1", "s-2", "f-1", ProjectAttributionStore.SOURCE_ATTRIBUTOR);
        verify(attributionStore).attribute("p-1", "s-2", "session", "c-1", ProjectAttributionStore.SOURCE_ATTRIBUTOR);
        verify(attributions).delete(anyString());
        verify(stateService).deleteSubjectEvents("s-1");
        verify(subjects).delete(anyString());
        verify(subjects).replace(any(ProjectSubject.class));   // alias written onto the target profile
    }

    @Test
    void mergeRejectsProposalTarget() {
        when(projectService.subject("p-1", "s-2")).thenReturn(subject("s-2", ProjectService.SUBJECT_PROPOSED, "Other"));

        assertThrows(BadRequestException.class, () -> service.mergeSubject("p-1", "user-1", false, "s-1", "s-2"));
    }

    @Test
    void rejectRequiresPendingProposal() {
        when(projectService.subject("p-1", "s-2")).thenReturn(subject("s-2", ProjectService.SUBJECT_STARTED, "Acme Spa"));

        assertThrows(BadRequestException.class, () -> service.rejectSubject("p-1", "user-1", false, "s-2"));
        verify(subjects, never()).delete(anyString());
    }

    @Test
    void rescanDropsUnattributedMarkersAndRewindsTheCursor() {
        when(scanStore.dropUnattributed("p-1")).thenReturn(new ProjectTargetScanStore.Rescan(T1, 3L));

        assertEquals(3L, service.rescanUnassigned("p-1", "user-1", false));

        // markers alone cannot reach records the cursor already passed
        verify(projectService).rewindAttributionBackfill("p-1", T1);
    }

    @Test
    void rescanLeavesTheCursorAloneWithoutMarkersToDrop() {
        when(scanStore.dropUnattributed("p-1")).thenReturn(new ProjectTargetScanStore.Rescan(null, 0L));

        assertEquals(0L, service.rescanUnassigned("p-1", "user-1", false));

        verify(projectService, never()).rewindAttributionBackfill(any(), any());
    }

    private ProjectSubject subject(String id, String status, String name) {
        var subject = new ProjectSubject();
        subject.id = id;
        subject.projectId = "p-1";
        subject.userId = "user-1";
        subject.name = name;
        subject.status = status;
        return subject;
    }

    private ProjectSubjectAttribution row(String id, String targetType, String targetId) {
        var row = new ProjectSubjectAttribution();
        row.id = id;
        row.projectId = "p-1";
        row.subjectId = "s-1";
        row.targetType = targetType;
        row.targetId = targetId;
        return row;
    }
}


