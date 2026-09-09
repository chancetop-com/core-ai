package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectMemberRef;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.run.LLMCallExecutor;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Proposer half of the attribution stage: a proposal becomes a subject only when the material backs
 * it (a run/report, never chat alone), the mode allows it and the normalized name is new.
 *
 * @author core-ai
 */
class ProjectAttributionStageTest {
    private ProjectAttributionStage stage;
    private MongoCollection<Project> projects;
    private MongoCollection<AgentRun> runs;
    private MongoCollection<ChatSession> sessions;
    private MongoCollection<ChatMessage> messages;
    private ProjectService projectService;
    private LLMCallExecutor llmCallExecutor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        stage = new ProjectAttributionStage();
        projects = (MongoCollection<Project>) mock(MongoCollection.class);
        stage.projectCollection = projects;
        runs = (MongoCollection<AgentRun>) mock(MongoCollection.class);
        stage.agentRunCollection = runs;
        sessions = (MongoCollection<ChatSession>) mock(MongoCollection.class);
        stage.chatSessionCollection = sessions;
        messages = (MongoCollection<ChatMessage>) mock(MongoCollection.class);
        stage.chatMessageCollection = messages;
        stage.workflowRunCollection = (MongoCollection<WorkflowRun>) mock(MongoCollection.class);
        stage.fileRecordCollection = (MongoCollection<FileRecord>) mock(MongoCollection.class);
        var agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        stage.agentCollection = agents;
        stage.attributionCollection = (MongoCollection<ProjectSubjectAttribution>) mock(MongoCollection.class);
        projectService = mock(ProjectService.class);
        stage.projectService = projectService;
        var scanStore = mock(ProjectTargetScanStore.class);
        stage.scanStore = scanStore;
        llmCallExecutor = mock(LLMCallExecutor.class);
        stage.llmCallExecutor = llmCallExecutor;

        when(projects.get("p-1")).thenReturn(Optional.of(project(null)));
        when(agents.get("builtin-" + ProjectBuiltinAgents.ATTRIBUTOR)).thenReturn(Optional.of(new AgentDefinition()));
        when(scanStore.states(anyString(), anyString(), any())).thenReturn(Map.of());
        when(sessions.find(any(Query.class))).thenReturn(List.of());
        when(messages.find(any(Query.class))).thenReturn(List.of());
        when(runs.find(any(Query.class))).thenReturn(List.of(run("run-1")));
        when(runs.get("run-1")).thenReturn(Optional.of(run("run-1")));
    }

    @Test
    void proposesSubjectBackedByRun() {
        when(projectService.subjects("p-1")).thenReturn(List.of());
        when(projectService.createAutoSubject("p-1", "Acme Spa", "the spa account", "run mentions acme", ProjectService.SUBJECT_PROPOSED))
            .thenReturn(subject("s-1"));
        when(llmCallExecutor.execute(any(), any(), any(), any())).thenReturn(new LLMCallExecutor.Result(proposal("Acme Spa", "run-1", "run"), 1, 1));

        stage.run("p-1");

        verify(projectService).createAutoSubject("p-1", "Acme Spa", "the spa account", "run mentions acme", ProjectService.SUBJECT_PROPOSED);
        verify(projectService).attribute("p-1", "s-1", "run", "run-1", ProjectAttributionStore.SOURCE_ATTRIBUTOR);
    }

    @Test
    void sendsPlaybookModeAndRejectedList() {
        when(projectService.subjects("p-1")).thenReturn(List.of());
        when(llmCallExecutor.execute(any(), any(), any(), any())).thenReturn(new LLMCallExecutor.Result("{}", 1, 1));

        stage.run("p-1");

        var input = ArgumentCaptor.forClass(String.class);
        verify(llmCallExecutor).execute(any(), input.capture(), any(), any());
        assertTrue(input.getValue().contains("PLAYBOOK:\nwatch the spa accounts"), input.getValue());
        assertTrue(input.getValue().contains("AUTO SUBJECTS: propose"), input.getValue());
        assertTrue(input.getValue().contains("REJECTED:\n- acme rival"), input.getValue());
    }

    @Test
    void skipsProposalsWhenModeIsOff() {
        when(projects.get("p-1")).thenReturn(Optional.of(project(ProjectService.AUTO_SUBJECTS_OFF)));
        when(projectService.subjects("p-1")).thenReturn(List.of());
        when(llmCallExecutor.execute(any(), any(), any(), any())).thenReturn(new LLMCallExecutor.Result(proposal("Acme Spa", "run-1", "run"), 1, 1));

        stage.run("p-1");

        verify(projectService, never()).createAutoSubject(any(), any(), any(), any(), any());
    }

    @Test
    void dropsProposalBackedByChatAlone() {
        when(runs.find(any(Query.class))).thenReturn(List.of());
        when(sessions.find(any(Query.class))).thenReturn(List.of(session("session-1")));
        when(messages.find(any(Query.class))).thenReturn(List.of(message("session-1", "tell me about acme spa")));
        when(projectService.subjects("p-1")).thenReturn(List.of());
        when(llmCallExecutor.execute(any(), any(), any(), any())).thenReturn(new LLMCallExecutor.Result(proposal("Acme Spa", "session-1", "session"), 1, 1));

        stage.run("p-1");

        verify(projectService, never()).createAutoSubject(any(), any(), any(), any(), any());
    }

    @Test
    void attributesToExistingSubjectOnNormalizedNameMatch() {
        when(projectService.subjects("p-1")).thenReturn(List.of(subject("s-9", "Acme Spa Inc")));
        when(llmCallExecutor.execute(any(), any(), any(), any())).thenReturn(new LLMCallExecutor.Result(proposal("acme spa", "run-1", "run"), 1, 1));

        stage.run("p-1");

        verify(projectService, never()).createAutoSubject(any(), any(), any(), any(), any());
        verify(projectService).attribute("p-1", "s-9", "run", "run-1", ProjectAttributionStore.SOURCE_ATTRIBUTOR);
    }

    @Test
    void dropsProposalOnTheRejectedList() {
        when(projectService.subjects("p-1")).thenReturn(List.of());
        when(llmCallExecutor.execute(any(), any(), any(), any())).thenReturn(new LLMCallExecutor.Result(proposal("Acme Rival", "run-1", "run"), 1, 1));

        stage.run("p-1");

        verify(projectService, never()).createAutoSubject(any(), any(), any(), any(), any());
    }

    @Test
    void dropsProposalForTargetThatWasNotOffered() {
        when(projectService.subjects("p-1")).thenReturn(List.of());
        when(llmCallExecutor.execute(any(), any(), any(), any())).thenReturn(new LLMCallExecutor.Result(proposal("Acme Spa", "run-9", "run"), 1, 1));

        stage.run("p-1");

        verify(projectService, never()).createAutoSubject(any(), any(), any(), any(), any());
    }

    @Test
    void createsStartedSubjectInCreateMode() {
        when(projects.get("p-1")).thenReturn(Optional.of(project(ProjectService.AUTO_SUBJECTS_CREATE)));
        when(projectService.subjects("p-1")).thenReturn(List.of());
        when(projectService.createAutoSubject(any(), any(), any(), any(), eq(ProjectService.SUBJECT_STARTED))).thenReturn(subject("s-1"));
        when(llmCallExecutor.execute(any(), any(), any(), any())).thenReturn(new LLMCallExecutor.Result(proposal("Acme Spa", "run-1", "run"), 1, 1));

        stage.run("p-1");

        verify(projectService).createAutoSubject("p-1", "Acme Spa", "the spa account", "run mentions acme", ProjectService.SUBJECT_STARTED);
    }

    private Project project(String autoSubjects) {
        var project = new Project();
        project.id = "p-1";
        project.userId = "user-1";
        project.name = "campaign";
        project.playbook = "watch the spa accounts";
        project.autoSubjects = autoSubjects;
        project.rejectedSubjectNames = List.of("acme rival");
        var member = new ProjectMemberRef();
        member.type = "agent";
        member.id = "agent-1";
        project.members = List.of(member);
        return project;
    }

    private AgentRun run(String id) {
        var run = new AgentRun();
        run.id = id;
        run.agentId = "agent-1";
        run.startedAt = ZonedDateTime.now();
        run.input = "analyze acme spa";
        return run;
    }

    private ChatSession session(String id) {
        var session = new ChatSession();
        session.id = id;
        session.agentId = "agent-1";
        session.title = "acme spa questions";
        session.lastMessageAt = ZonedDateTime.now();
        return session;
    }

    private ChatMessage message(String sessionId, String content) {
        var message = new ChatMessage();
        message.sessionId = sessionId;
        message.role = "user";
        message.content = content;
        return message;
    }

    private ProjectSubject subject(String id) {
        return subject(id, "Acme Spa");
    }
    private ProjectSubject subject(String id, String name) {
        var subject = new ProjectSubject();
        subject.id = id;
        subject.projectId = "p-1";
        subject.userId = "user-1";
        subject.name = name;
        return subject;
    }

    private String proposal(String name, String targetId, String targetType) {
        return """
            {"attributions":[],"new_subjects":[{"name":"%s","description":"the spa account","reason":"run mentions acme","targets":[{"target_type":"%s","target_id":"%s"}]}]}""".formatted(name, targetType, targetId);
    }
}
