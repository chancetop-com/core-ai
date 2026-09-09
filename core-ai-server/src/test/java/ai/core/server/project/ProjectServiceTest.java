package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.WorkflowDefinition;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.BadRequestException;
import org.bson.conversions.Bson;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Project scaffold unit tests: subject CRUD, membership and the attribution table
 * (the subject-state write surface is covered by {@link ProjectStateServiceTest}).
 *
 * @author core-ai
 */
class ProjectServiceTest {
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

    private ProjectService service;
    private MongoCollection<Project> projects;
    private MongoCollection<ProjectSubject> subjects;
    private MongoCollection<ProjectSubjectAttribution> attributions;
    private MongoCollection<AgentDefinition> agents;
    private ProjectAttributionStore attributionStore;
    private ProjectArtifactBinder artifactBinder;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        service = new ProjectService();
        projects = (MongoCollection<Project>) mock(MongoCollection.class);
        subjects = (MongoCollection<ProjectSubject>) mock(MongoCollection.class);
        service.projectCollection = projects;
        service.subjectCollection = subjects;
        attributions = (MongoCollection<ProjectSubjectAttribution>) mock(MongoCollection.class);
        service.attributionCollection = attributions;
        agents = (MongoCollection<AgentDefinition>) mock(MongoCollection.class);
        service.agentCollection = agents;
        var workflowCollection = (MongoCollection<WorkflowDefinition>) mock(MongoCollection.class);
        service.workflowCollection = workflowCollection;
        service.permissionService = mock(ai.core.server.apiuser.PermissionService.class);
        var userCollection = (MongoCollection<ai.core.server.domain.User>) mock(MongoCollection.class);
        service.userCollection = userCollection;
        service.stateService = mock(ProjectStateService.class);
        attributionStore = mock(ProjectAttributionStore.class);
        service.attributionStore = attributionStore;
        artifactBinder = mock(ProjectArtifactBinder.class);
        service.artifactBinder = artifactBinder;
        when(attributionStore.attribute(any(), any(), any(), any(), any())).thenReturn(ProjectAttributionStore.Result.INSERTED);
        when(projects.get("p-1")).thenReturn(Optional.of(project("p-1")));
        when(subjects.count(any(Bson.class))).thenReturn(0L);
        when(attributions.count(any(Bson.class))).thenReturn(0L);
    }

    @Test
    void deleteSubjectBlocksWhenAttributed() {
        when(projects.get("p-1")).thenReturn(Optional.of(project("p-1")));
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        when(attributions.count(any(Bson.class))).thenReturn(1L);

        assertThrows(BadRequestException.class, () -> service.deleteSubject("p-1", "user-1", false, "s-1"));
    }

    @Test
    void attributeValidatesTargetType() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        assertThrows(BadRequestException.class, () -> service.attribute("p-1", "s-1", "trace", "t-1"));
    }

    @Test
    void attributeInsertsAttributionAndCascadesToArtifacts() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        service.attribute("p-1", "s-1", "session", "session-1");
        verify(attributionStore).attribute("p-1", "s-1", "session", "session-1", ProjectAttributionStore.SOURCE_ATTRIBUTOR);
        verify(artifactBinder).cascade("p-1", "s-1", "session", "session-1");
    }

    @Test
    void attributeRejectsFileAlreadyHomedElsewhere() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        when(attributionStore.attribute("p-1", "s-1", "file", "f-1", ProjectAttributionStore.SOURCE_ATTRIBUTOR))
            .thenReturn(ProjectAttributionStore.Result.CONFLICT);
        assertThrows(BadRequestException.class, () -> service.attribute("p-1", "s-1", "file", "f-1"));
        verify(artifactBinder, never()).cascade(any(), any(), any(), any());
    }

    @Test
    void moveReportRehomesFileAndAllowsUnassigning() {
        when(subjects.get("s-1")).thenReturn(Optional.of(subject("s-1")));
        service.moveReport("p-1", "user-1", false, "f-1", "s-1");
        verify(attributionStore).moveFile("p-1", "s-1", "f-1", ProjectAttributionStore.SOURCE_MANUAL);
        service.moveReport("p-1", "user-1", false, "f-1", "");
        verify(attributionStore).moveFile("p-1", null, "f-1", ProjectAttributionStore.SOURCE_MANUAL);
    }

    @Test
    void addMemberWritesMembersWithTargetedUpdate() {
        var stored = project("p-1");
        when(projects.get("p-1")).thenReturn(Optional.of(stored));
        var agent = new AgentDefinition();
        agent.id = "agent-1";
        agent.userId = "user-1";
        agent.name = "audit-agent";
        when(agents.get("agent-1")).thenReturn(Optional.of(agent));

        service.addMember("p-1", "user-1", false, "agent", "agent-1");

        // membership is written with a targeted $set (never a whole-document replace, which would
        // clobber the analysis cursors/claims the jobs update concurrently)
        verify(projects).update(any(Bson.class), any(Bson.class));
        verify(projects, never()).replace(any());
    }

    @Test
    void addMemberRejectsUnpublishedAgentOwnedByAnotherUser() {
        var agent = new AgentDefinition();
        agent.id = "agent-1";
        agent.userId = "another-user";
        when(agents.get("agent-1")).thenReturn(Optional.of(agent));

        assertThrows(core.framework.web.exception.ForbiddenException.class,
            () -> service.addMember("p-1", "user-1", false, "agent", "agent-1"));
    }

    @Test
    void addMemberAllowsPublishedSharedAgent() {
        var stored = project("p-1");
        when(projects.get("p-1")).thenReturn(Optional.of(stored));
        var agent = new AgentDefinition();
        agent.id = "agent-1";
        agent.userId = "another-user";
        agent.status = AgentStatus.PUBLISHED;
        agent.publishedConfig = new ai.core.server.domain.AgentPublishedConfig();
        when(agents.get("agent-1")).thenReturn(Optional.of(agent));

        service.addMember("p-1", "user-1", false, "agent", "agent-1");

        verify(projects).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void addMemberRejectsUnknownType() {
        assertThrows(BadRequestException.class, () -> service.addMember("p-1", "user-1", false, "dataset", "d-1"));
    }

    @Test
    void addMemberAllowsManagePermissionHolder() {
        var stored = project("p-1");
        when(projects.get("p-1")).thenReturn(Optional.of(stored));
        var agent = new AgentDefinition();
        agent.id = "agent-1";
        agent.userId = "agent-owner";
        agent.name = "shared-agent";
        agent.status = AgentStatus.PUBLISHED;
        agent.publishedConfig = new ai.core.server.domain.AgentPublishedConfig();
        when(agents.get("agent-1")).thenReturn(Optional.of(agent));
        when(service.permissionService.has("user-2", "project.manage")).thenReturn(Boolean.TRUE);

        service.addMember("p-1", "user-2", false, "agent", "agent-1");

        verify(projects).update(any(Bson.class), any(Bson.class));
    }

    @Test
    void requireViewRejectsUnrelatedUser() {
        var stored = project("p-1");
        assertThrows(core.framework.web.exception.ForbiddenException.class,
            () -> service.requireView(stored, "user-3"));
    }

    @Test
    void removeMemberWritesMembersWithTargetedUpdate() {
        var stored = project("p-1");
        var member = new ai.core.server.domain.ProjectMemberRef();
        member.type = "workflow";
        member.id = "wf-1";
        member.name = "seo-workflow";
        stored.members = new ArrayList<>(java.util.List.of(member));
        when(projects.get("p-1")).thenReturn(Optional.of(stored));

        service.removeMember("p-1", "user-1", false, "workflow", "wf-1");

        verify(projects).update(any(Bson.class), any(Bson.class));
        verify(projects, never()).replace(any());
    }
}
