package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectActionItem;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectEvent;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Subject-state write surface unit tests: subject validation (D5), current state on the subject
 * document, the append-only event rows (D7) behind every change, idempotent KPI/note events and
 * the no-regression rule for older material.
 *
 * @author core-ai
 */
class ProjectStateServiceTest {
    private static Project project(String id) {
        var project = new Project();
        project.id = id;
        project.userId = "user-1";
        project.name = "campaign";
        project.status = ProjectService.STATUS_ACTIVE;
        return project;
    }

    private static ProjectSubject subject(String id) {
        var subject = new ProjectSubject();
        subject.id = id;
        subject.projectId = "p-1";
        subject.userId = "user-1";
        subject.name = "merchant";
        return subject;
    }

    private ProjectStateService service;
    private MongoCollection<Project> projects;
    private MongoCollection<ProjectSubject> subjects;
    private MongoCollection<ProjectSubjectEvent> events;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ProjectStateService();
        projects = (MongoCollection<Project>) mock(MongoCollection.class);
        subjects = (MongoCollection<ProjectSubject>) mock(MongoCollection.class);
        events = (MongoCollection<ProjectSubjectEvent>) mock(MongoCollection.class);
        service.projectCollection = projects;
        service.subjectCollection = subjects;
        service.eventCollection = events;
        when(projects.get("p-1")).thenReturn(Optional.of(project("p-1")));
        when(events.count(any(Bson.class))).thenReturn(0L);
    }

    @Test
    void updateStatusRequiresSubject() {
        assertThrows(BadRequestException.class, () -> service.updateStatus("p-1", null, "phase-1", "summary-1", null, "agent-1"));
    }

    @Test
    void updateStatusWritesSubjectAndAppendsEvents() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));

        service.updateStatus("p-1", "s-1", "phase-1", "summary-1", null, "agent-1");

        verify(subjects).update(any(Bson.class), any(Bson.class));
        var captor = forClass(ProjectSubjectEvent.class);
        verify(events, times(2)).insert(captor.capture());   // phase-1 + summary-1
        assertEquals(ProjectSubjectEvent.TYPE_PHASE, captor.getAllValues().getFirst().type);
        assertEquals("phase-1", captor.getAllValues().getFirst().value);
        assertEquals(ProjectSubjectEvent.TYPE_SUMMARY, captor.getAllValues().getLast().type);
        assertEquals("phase-1", captor.getAllValues().getLast().key);   // summary event keyed by the phase it belongs to
    }

    @Test
    void updateStatusUnchangedEmitsNoEvents() {
        var stored = subject("s-1");
        stored.phase = "phase-1";
        stored.summary = "summary-1";
        when(subjects.get("s-1")).thenReturn(Optional.of(stored));

        service.updateStatus("p-1", "s-1", "phase-1", "summary-1", null, "agent-1");

        verify(events, never()).insert(any());
    }

    @Test
    void updateStatusUsesMaterialTimeForEvents() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        var materialAt = ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, java.time.ZoneId.systemDefault());

        service.updateStatus("p-1", "s-1", "phase-1", "summary-1", materialAt, "agent-1");

        var captor = forClass(ProjectSubjectEvent.class);
        verify(events, times(2)).insert(captor.capture());
        assertEquals(materialAt, captor.getAllValues().getFirst().at);   // event time = material time
    }

    @Test
    void updateStatusOlderMaterialKeepsHistoryButDoesNotRegressCurrentState() {
        var stored = subject("s-1");
        stored.phase = "executing";
        stored.statusUpdatedAt = ZonedDateTime.now();
        when(subjects.get("s-1")).thenReturn(Optional.of(stored));

        service.updateStatus("p-1", "s-1", "audit", null, ZonedDateTime.now().minusDays(10), "agent-1");

        verify(events).insert(any());                                   // the older phase is still a fact of history
        verify(subjects, never()).update(any(Bson.class), any(Bson.class));   // but the current state stays newer
    }

    @Test
    void updateStatusRejectsUnknownSubject() {
        when(subjects.get("s-unknown")).thenReturn(Optional.empty());
        assertThrows(BadRequestException.class, () -> service.updateStatus("p-1", "s-unknown", "phase", "summary", null, "agent-1"));
    }

    @Test
    void recordKpiRequiresSubject() {
        assertThrows(BadRequestException.class,
            () -> service.recordKpi("p-1", null, "agent-1", null, new ProjectStateService.KpiSnapshot("audit_score", "6.2", null)));
    }

    @Test
    void recordKpiWritesEventOnly() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        service.recordKpi("p-1", "s-1", "agent-1", null, new ProjectStateService.KpiSnapshot("audit_score", "6.2", "pts"));
        var captor = forClass(ProjectSubjectEvent.class);
        verify(events).insert(captor.capture());
        assertEquals("audit_score", captor.getValue().key);
        assertEquals("6.2", captor.getValue().value);
        verify(subjects, never()).update(any(Bson.class), any(Bson.class));
        verify(projects, never()).replace(any());
    }

    @Test
    void recordKpiSkipsDuplicateObservation() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        when(events.count(any(Bson.class))).thenReturn(1L);
        service.recordKpi("p-1", "s-1", "agent-1", null, new ProjectStateService.KpiSnapshot("audit_score", "6.2", null));
        verify(events, never()).insert(any());
    }

    @Test
    void recordKpiRejectsUnknownSubject() {
        when(subjects.get("s-unknown")).thenReturn(Optional.empty());
        assertThrows(BadRequestException.class,
            () -> service.recordKpi("p-1", "s-unknown", "agent-1", null, new ProjectStateService.KpiSnapshot("k", "v", null)));
    }

    @Test
    void updateActionItemRequiresSubject() {
        assertThrows(BadRequestException.class,
            () -> service.updateActionItem("p-1", "agent-1", new ProjectStateService.ActionItemFields(null, null, "fix links", null, null, null)));
    }

    @Test
    void updateActionItemCreatesThenUpdates() {
        var stored = subject("s-1");
        when(subjects.get("s-1")).thenReturn(Optional.of(stored));

        service.updateActionItem("p-1", "agent-1", new ProjectStateService.ActionItemFields("s-1", null, "fix links", null, null, null));
        var created = forClass(ProjectSubjectEvent.class);
        verify(events).insert(created.capture());
        assertEquals("open", created.getValue().value);   // default status

        var item = new ProjectActionItem();
        item.id = created.getValue().key;
        item.subjectId = "s-1";
        item.title = "fix links";
        item.status = "open";
        stored.actionItems = new ArrayList<>(java.util.List.of(item));
        service.updateActionItem("p-1", "agent-1", new ProjectStateService.ActionItemFields("s-1", item.id, "fix links", "done", "all fixed", null));

        verify(subjects, times(2)).update(any(Bson.class), any(Bson.class));
        verify(events, times(2)).insert(any());   // created + status change
    }

    @Test
    void updateActionItemUnchangedEmitsNoEvent() {
        var stored = subject("s-1");
        var item = new ProjectActionItem();
        item.id = "a-1";
        item.subjectId = "s-1";
        item.title = "fix links";
        item.status = "open";
        stored.actionItems = new ArrayList<>(java.util.List.of(item));
        when(subjects.get("s-1")).thenReturn(Optional.of(stored));

        service.updateActionItem("p-1", "agent-1", new ProjectStateService.ActionItemFields("s-1", "a-1", "fix links", "open", null, null));

        verify(events, never()).insert(any());
    }

    @Test
    void addNoteRequiresSubject() {
        assertThrows(BadRequestException.class, () -> service.addNote("p-1", null, "content", null, "agent-1"));
    }

    @Test
    void addNoteRejectsBlankContent() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        assertThrows(BadRequestException.class, () -> service.addNote("p-1", "s-1", "   ", null, "agent-1"));
    }
}
