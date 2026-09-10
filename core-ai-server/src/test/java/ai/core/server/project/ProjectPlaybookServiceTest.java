package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentRun;
import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatMessage;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.FileRecord;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectMemberRef;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowRun;
import ai.core.server.run.LLMCallExecutor;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The playbook draft is one LLM call over material the user never typed into the project: the member
 * definitions, the tracked subjects and samples of what the members actually produced. The tests pin
 * that evidence down (a draft without it is a generic essay) and the cleanup of the model's reply.
 *
 * @author core-ai
 */
class ProjectPlaybookServiceTest {
    private ProjectPlaybookService service;
    private MongoCollection<ChatSession> sessions;
    private MongoCollection<ChatMessage> messages;
    private MongoCollection<FileRecord> files;
    private MongoCollection<AgentDefinition> agents;
    private MongoCollection<WorkflowDefinition> workflows;
    private ProjectService projectService;
    private LLMCallExecutor executor;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ProjectPlaybookService();
        var projects = (MongoCollection<Project>) mock(MongoCollection.class);
        service.projectCollection = projects;
        sessions = (MongoCollection<ChatSession>) mock(MongoCollection.class);
        service.chatSessionCollection = sessions;
        messages = (MongoCollection<ChatMessage>) mock(MongoCollection.class);
        service.chatMessageCollection = messages;
        var runs = (MongoCollection<AgentRun>) mock(MongoCollection.class);
        service.agentRunCollection = runs;
        var workflowRuns = (MongoCollection<WorkflowRun>) mock(MongoCollection.class);
        service.workflowRunCollection = workflowRuns;
        files = (MongoCollection<FileRecord>) mock(MongoCollection.class);
        service.fileRecordCollection = files;
        agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        service.agentCollection = agents;
        workflows = (MongoCollection<WorkflowDefinition>) mock(MongoCollection.class);
        service.workflowCollection = workflows;
        projectService = mock(ProjectService.class);
        service.projectService = projectService;
        executor = mock(LLMCallExecutor.class);
        service.llmCallExecutor = executor;

        when(projects.get("p-1")).thenReturn(Optional.of(project()));
        when(agents.get("builtin-" + ProjectBuiltinAgents.PLAYBOOK_WRITER)).thenReturn(Optional.of(new AgentDefinition()));
        when(agents.find(any(Bson.class))).thenReturn(List.of());
        when(workflows.find(any(Bson.class))).thenReturn(List.of());
        when(sessions.find(any(Query.class))).thenReturn(List.of());
        when(runs.find(any(Query.class))).thenReturn(List.of());
        when(workflowRuns.find(any(Query.class))).thenReturn(List.of());
        when(files.find(any(Bson.class))).thenReturn(List.of());
        when(projectService.subjects("p-1")).thenReturn(List.of());
    }

    @Test
    void draftIsBuiltFromMembersSubjectsAndMaterial() {
        when(projectService.subjects("p-1")).thenReturn(List.of(subject("Acme Spa", "monthly spa ads")));
        when(agents.find(any(Bson.class))).thenReturn(List.of(agent("a-1", "runs Meta campaigns for spa clients")));
        when(workflows.find(any(Bson.class))).thenReturn(List.of(workflow("w-1", "collects weekly performance data")));
        when(sessions.find(any(Query.class))).thenReturn(List.of(session("c-1", "Acme Spa campaign", "a-1")));
        when(messages.find(any(Query.class))).thenReturn(List.of(
            message("user", "run the monthly report for Acme Spa"),
            message("assistant", "done")));
        when(files.find(any(Bson.class))).thenReturn(List.of(file("f-1", "Acme Spa 2026-08 report.html")));
        when(executor.execute(any(), anyString(), any(), any())).thenReturn(new LLMCallExecutor.Result("# Playbook\n...", 10, 20));

        assertEquals("# Playbook\n...", service.generate("p-1"));

        var input = capturedInput();
        assertTrue(input.contains("Acme Spa"), input);
        assertTrue(input.contains("runs Meta campaigns for spa clients"), input);
        assertTrue(input.contains("collects weekly performance data"), input);
        assertTrue(input.contains("run the monthly report for Acme Spa"), input);
        assertTrue(input.contains("Acme Spa 2026-08 report.html"), input);
    }

    @Test
    void fencedReplyIsUnwrapped() {
        when(executor.execute(any(), anyString(), any(), any()))
            .thenReturn(new LLMCallExecutor.Result("```markdown\n# Playbook\n- subject: merchant\n```", 1, 1));

        assertEquals("# Playbook\n- subject: merchant", service.generate("p-1"));
    }

    @Test
    void missingDefinitionPointsAtTheResetAction() {
        when(agents.get("builtin-" + ProjectBuiltinAgents.PLAYBOOK_WRITER)).thenReturn(Optional.empty());

        var failure = assertThrows(IllegalStateException.class, () -> service.generate("p-1"));
        assertTrue(failure.getMessage().contains("reset builtin agents"), failure.getMessage());
    }

    @Test
    void emptyDraftFails() {
        when(executor.execute(any(), anyString(), any(), any())).thenReturn(new LLMCallExecutor.Result("   \n", 1, 1));

        var failure = assertThrows(IllegalStateException.class, () -> service.generate("p-1"));
        assertTrue(failure.getMessage().contains("empty"), failure.getMessage());
    }

    private String capturedInput() {
        var captor = ArgumentCaptor.forClass(String.class);
        verify(executor).execute(any(), captor.capture(), any(), any());
        return captor.getValue();
    }

    private Project project() {
        var project = new Project();
        project.id = "p-1";
        project.name = "Spa marketing";
        project.goal = "grow bookings";
        project.members = List.of(member("a-1", "agent", "spa-ad-agent"), member("w-1", "workflow", "weekly-data"));
        return project;
    }

    private ProjectMemberRef member(String id, String type, String name) {
        var member = new ProjectMemberRef();
        member.id = id;
        member.type = type;
        member.name = name;
        return member;
    }

    private ProjectSubject subject(String name, String description) {
        var subject = new ProjectSubject();
        subject.id = "s-1";
        subject.name = name;
        subject.description = description;
        return subject;
    }

    private AgentDefinition agent(String id, String description) {
        var definition = new AgentDefinition();
        definition.id = id;
        definition.description = description;
        return definition;
    }

    private WorkflowDefinition workflow(String id, String description) {
        var definition = new WorkflowDefinition();
        definition.id = id;
        definition.description = description;
        return definition;
    }

    private ChatSession session(String id, String title, String agentId) {
        var session = new ChatSession();
        session.id = id;
        session.title = title;
        session.agentId = agentId;
        var artifact = new AgentRunArtifact();
        artifact.fileId = "f-1";
        session.artifacts = List.of(artifact);
        return session;
    }

    private ChatMessage message(String role, String content) {
        var message = new ChatMessage();
        message.role = role;
        message.content = content;
        return message;
    }

    private FileRecord file(String id, String fileName) {
        var record = new FileRecord();
        record.id = id;
        record.fileName = fileName;
        return record;
    }
}
