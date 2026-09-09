package ai.core.server.project;

import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.AgentSchedule;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import core.framework.mongo.MongoCollection;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author stephen
 */
class ProjectArtifactBinderTest {
    private static ProjectSubjectAttribution row(String projectId, String subjectId, String type, String targetId) {
        var row = new ProjectSubjectAttribution();
        row.projectId = projectId;
        row.subjectId = subjectId;
        row.targetType = type;
        row.targetId = targetId;
        return row;
    }

    private static ProjectSubject subject(String id, String projectId) {
        var subject = new ProjectSubject();
        subject.id = id;
        subject.projectId = projectId;
        return subject;
    }

    private static AgentRunArtifact artifact(String fileId) {
        var artifact = new AgentRunArtifact();
        artifact.fileId = fileId;
        return artifact;
    }

    private ProjectArtifactBinder binder;
    private ProjectAttributionStore store;
    private MongoCollection<AgentSchedule> schedules;
    private MongoCollection<ChatSession> sessions;
    private MongoCollection<AgentRun> runs;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        binder = new ProjectArtifactBinder();
        store = mock(ProjectAttributionStore.class);
        schedules = (MongoCollection<AgentSchedule>) mock(MongoCollection.class);
        sessions = (MongoCollection<ChatSession>) mock(MongoCollection.class);
        runs = (MongoCollection<AgentRun>) mock(MongoCollection.class);
        binder.store = store;
        binder.scheduleCollection = schedules;
        binder.chatSessionCollection = sessions;
        binder.agentRunCollection = runs;
        when(store.byTarget(anyString(), anyString())).thenReturn(List.of());
        when(store.attribute(any(), any(), any(), any(), any())).thenReturn(ProjectAttributionStore.Result.INSERTED);
        when(store.subject(any())).thenReturn(Optional.empty());
    }

    @Test
    void boundScheduleAttributesRunAtCreation() {
        var schedule = new AgentSchedule();
        schedule.id = "sch-1";
        schedule.projectId = "p-1";
        schedule.subjectId = "s-1";
        when(schedules.get("sch-1")).thenReturn(Optional.of(schedule));
        when(store.subject("s-1")).thenReturn(Optional.of(subject("s-1", "p-1")));
        var run = new AgentRun();
        run.id = "run-1";
        run.scheduleId = "sch-1";

        binder.bindRun(run);

        verify(store).attribute("p-1", "s-1", "run", "run-1", ProjectAttributionStore.SOURCE_SCHEDULE);
    }

    @Test
    void unboundOrStaleScheduleIsIgnored() {
        var unbound = new AgentSchedule();
        unbound.id = "sch-1";
        when(schedules.get("sch-1")).thenReturn(Optional.of(unbound));
        var run = new AgentRun();
        run.id = "run-1";
        run.scheduleId = "sch-1";
        binder.bindRun(run);

        // subject moved to another project after the schedule was bound
        var stale = new AgentSchedule();
        stale.id = "sch-2";
        stale.projectId = "p-1";
        stale.subjectId = "s-1";
        when(schedules.get("sch-2")).thenReturn(Optional.of(stale));
        when(store.subject("s-1")).thenReturn(Optional.of(subject("s-1", "p-other")));
        run.scheduleId = "sch-2";
        binder.bindRun(run);

        verify(store, never()).attribute(any(), any(), any(), any(), any());
    }

    @Test
    void artifactInheritsSingleSubjectOfItsSession() {
        when(store.byTarget("session", "sess-1")).thenReturn(List.of(row("p-1", "s-1", "session", "sess-1")));

        binder.onArtifact("session", "sess-1", "f-1");

        verify(store).attribute("p-1", "s-1", "file", "f-1", ProjectAttributionStore.SOURCE_INHERITED);
    }

    @Test
    void ambiguousParentLeavesArtifactUnassigned() {
        when(store.byTarget("session", "sess-1")).thenReturn(List.of(
            row("p-1", "s-1", "session", "sess-1"), row("p-1", "s-2", "session", "sess-1"), row("p-2", "s-9", "session", "sess-1")));

        binder.onArtifact("session", "sess-1", "f-1");

        // project p-1 is ambiguous (two subjects) -> skipped; project p-2 is unambiguous -> inherited
        verify(store, never()).attribute(eq("p-1"), any(), any(), any(), any());
        verify(store).attribute("p-2", "s-9", "file", "f-1", ProjectAttributionStore.SOURCE_INHERITED);
    }

    @Test
    void artifactOfUnattributedScheduledRunResolvesScheduleFirst() {
        var run = new AgentRun();
        run.id = "run-1";
        run.scheduleId = "sch-1";
        when(runs.get("run-1")).thenReturn(Optional.of(run));
        var schedule = new AgentSchedule();
        schedule.id = "sch-1";
        schedule.subjectId = "s-1";
        when(schedules.get("sch-1")).thenReturn(Optional.of(schedule));
        when(store.subject("s-1")).thenReturn(Optional.of(subject("s-1", "p-1")));
        // first lookup: nothing; after bindRun the run row exists
        var bound = List.of(row("p-1", "s-1", "run", "run-1"));
        when(store.byTarget("run", "run-1")).thenReturn(List.of()).thenReturn(bound);

        binder.onArtifact("run", "run-1", "f-1");

        verify(store).attribute("p-1", "s-1", "run", "run-1", ProjectAttributionStore.SOURCE_SCHEDULE);
        verify(store).attribute("p-1", "s-1", "file", "f-1", ProjectAttributionStore.SOURCE_INHERITED);
    }

    @Test
    void cascadeAttributesExistingArtifactsAndCountsInserts() {
        var run = new AgentRun();
        run.id = "run-1";
        run.artifacts = List.of(artifact("f-1"), artifact("f-2"), artifact("f-1"));
        when(runs.get("run-1")).thenReturn(Optional.of(run));
        when(store.byTarget("run", "run-1")).thenReturn(List.of(row("p-1", "s-1", "run", "run-1")));
        when(store.attribute("p-1", "s-1", "file", "f-2", ProjectAttributionStore.SOURCE_CASCADE)).thenReturn(ProjectAttributionStore.Result.CONFLICT);

        int count = binder.cascade("p-1", "s-1", "run", "run-1");

        assertEquals(1, count);
        verify(store).attribute("p-1", "s-1", "file", "f-1", ProjectAttributionStore.SOURCE_CASCADE);
        verify(store).attribute("p-1", "s-1", "file", "f-2", ProjectAttributionStore.SOURCE_CASCADE);
    }

    @Test
    void cascadeSkipsParentSpanningSeveralSubjects() {
        var session = new ChatSession();
        session.id = "sess-1";
        session.artifacts = List.of(artifact("f-1"));
        when(sessions.get("sess-1")).thenReturn(Optional.of(session));
        when(store.byTarget("session", "sess-1")).thenReturn(List.of(row("p-1", "s-1", "session", "sess-1"), row("p-1", "s-2", "session", "sess-1")));

        assertEquals(0, binder.cascade("p-1", "s-2", "session", "sess-1"));
        verify(store, never()).attribute(any(), any(), eq("file"), any(), any());
    }
}
