package ai.core.server.project;

import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectKpiRecord;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentCaptor.forClass;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
/**
 * Subject-state write surface unit tests: subject validation (D5), append/update semantics,
 * limits and the append-only event rows (D7) behind every change.
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
        project.kpis = new ArrayList<>();
        project.actionItems = new ArrayList<>();
        project.notes = new ArrayList<>();
        project.subjectStatuses = new ArrayList<>();
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
    }

    @Test
    void updateStatusRequiresSubject() {
        assertThrows(BadRequestException.class, () -> service.updateStatus("p-1", null, "phase-1", "summary-1", null, "agent-1"));
    }

    @Test
    void updateStatusWritesSubjectStatus() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        var stored = project("p-1");
        when(projects.get("p-1")).thenReturn(Optional.of(stored));

        service.updateStatus("p-1", "s-1", "phase-1", "summary-1", null, "agent-1");

        assertEquals(1, stored.subjectStatuses.size());
        assertEquals("s-1", stored.subjectStatuses.getFirst().subjectId);
        assertEquals("summary-1", stored.subjectStatuses.getFirst().summary);
    }

    @Test
    void updateStatusAppendsPhaseAndSummaryEvents() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        var stored = project("p-1");
        when(projects.get("p-1")).thenReturn(Optional.of(stored));

        service.updateStatus("p-1", "s-1", "phase-1", "summary-1", null, "agent-1");
        service.updateStatus("p-1", "s-1", "phase-2", "summary-2", null, "agent-1");
        service.updateStatus("p-1", "s-1", "phase-2", "summary-2", null, "agent-1");   // unchanged: no events

        verify(events, times(4)).insert(any());   // phase-1, summary-1, phase-2, summary-2
    }

    @Test
    void updateStatusUsesMaterialTimeForEvents() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        var stored = project("p-1");
        when(projects.get("p-1")).thenReturn(Optional.of(stored));
        var materialAt = ZonedDateTime.of(2026, 6, 1, 10, 0, 0, 0, java.time.ZoneId.systemDefault());

        service.updateStatus("p-1", "s-1", "phase-1", "summary-1", materialAt, "agent-1");

        var captor = forClass(ProjectSubjectEvent.class);
        verify(events, times(2)).insert(captor.capture());
        assertEquals(materialAt, captor.getAllValues().getFirst().at);           // event time = material time
        assertEquals(materialAt, stored.subjectStatuses.getFirst().updatedAt);   // embedded row follows suit
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
    void recordKpiWritesSubjectKpi() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        service.recordKpi("p-1", "s-1", "agent-1", null, new ProjectStateService.KpiSnapshot("audit_score", "6.2", null));
        verify(projects).update(any(Bson.class), any(Bson.class));
        verify(events).insert(any());
    }

    @Test
    void recordKpiRejectsUnknownSubject() {
        when(subjects.get("s-unknown")).thenReturn(Optional.empty());
        assertThrows(BadRequestException.class,
            () -> service.recordKpi("p-1", "s-unknown", "agent-1", null, new ProjectStateService.KpiSnapshot("k", "v", null)));
    }

    @Test
    void recordKpiAtCapStillRecordsEvent() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        var stored = project("p-1");
        for (int i = 0; i < ProjectStateService.MAX_KPIS; i++) {
            var kpi = new ProjectKpiRecord();
            kpi.subjectId = "s-1";
            kpi.key = "k";
            kpi.value = "v";
            kpi.createdAt = ZonedDateTime.now();
            stored.kpis.add(kpi);
        }
        when(projects.get("p-1")).thenReturn(Optional.of(stored));

        service.recordKpi("p-1", "s-1", "agent-1", null, new ProjectStateService.KpiSnapshot("k", "v", null));

        verify(events).insert(any());                 // the event series must survive the embedded cap
        verify(projects, never()).update(any(Bson.class), any(Bson.class));   // embedded array untouched at cap
    }

    @Test
    void updateActionItemRequiresSubject() {
        assertThrows(BadRequestException.class,
            () -> service.updateActionItem("p-1", "agent-1", new ProjectStateService.ActionItemFields(null, null, "fix links", null, null, null)));
    }

    @Test
    void updateActionItemCreatesThenUpdates() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        var stored = project("p-1");
        when(projects.get("p-1")).thenReturn(Optional.of(stored));

        service.updateActionItem("p-1", "agent-1", new ProjectStateService.ActionItemFields("s-1", null, "fix links", null, null, null));
        assertEquals(1, stored.actionItems.size());
        var item = stored.actionItems.getFirst();
        assertNotNull(item.id);
        assertEquals("s-1", item.subjectId);
        assertEquals("open", item.status);

        service.updateActionItem("p-1", "agent-1", new ProjectStateService.ActionItemFields("s-1", item.id, "fix links", "done", "all fixed", null));
        assertEquals("done", stored.actionItems.getFirst().status);
        assertEquals("all fixed", stored.actionItems.getFirst().note);
        verify(projects, times(2)).replace(any());
        verify(events, times(2)).insert(any());   // created + status change
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
