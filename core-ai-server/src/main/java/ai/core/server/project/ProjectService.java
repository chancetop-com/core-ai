package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectMemberRef;
import ai.core.server.domain.ProjectReportSource;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.agent.AgentDependencyAccessPolicy;
import ai.core.server.workflow.WorkflowDefinitionService;
import com.mongodb.MongoWriteException;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Sorts;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.NotFoundException;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Project (campaign container) domain service: CRUD, subject and membership management, the
 * attribution table and the subject-state write surface. Subject state writes (status/KPIs/
 * action items/notes + history events) live in {@link ProjectStateService}; read-side
 * aggregations live in {@link ProjectQueryService}.
 *
 * @author stephen
 */
public class ProjectService {
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_PAGE_LIMIT = 200;
    static final String STATUS_ACTIVE = "active";
    static final String STATUS_ARCHIVED = "archived";
    static final String ANALYSIS_RUNNING = "running";
    private static final int DUPLICATE_KEY_CODE = 11000;

    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    MongoCollection<AgentDefinition> agentCollection;
    @Inject
    MongoCollection<WorkflowDefinition> workflowCollection;
    @Inject
    MongoCollection<ai.core.server.domain.User> userCollection;
    @Inject
    ai.core.server.apiuser.PermissionService permissionService;
    @Inject
    ProjectStateService stateService;

    public Project create(String userId, String name, String description, String goal) {
        var project = new Project();
        project.id = UUID.randomUUID().toString();
        project.userId = userId;
        project.name = name.trim();
        project.description = blankToNull(description);
        project.goal = blankToNull(goal);
        project.status = STATUS_ACTIVE;
        project.statsDirty = Boolean.TRUE;
        project.createdAt = ZonedDateTime.now();
        project.updatedAt = project.createdAt;
        projectCollection.insert(project);
        return project;
    }

    public Project require(String id) {
        return projectCollection.get(id).orElseThrow(() -> new NotFoundException("project not found, id=" + id));
    }

    // a project is a shared business container: owner and admins always pass; everyone else needs
    // the RBAC permission code (project.view / project.manage)
    public void requireAccess(Project project, String userId, boolean admin) {
        if (admin || ProjectAccess.canManage(project, userId, permissionService, userCollection)) return;
        throw new ForbiddenException("permission required: " + ai.core.server.rbac.PermissionCodes.PROJECT_MANAGE);
    }

    public void requireView(Project project, String userId) {
        if (ProjectAccess.canView(project, userId, permissionService, userCollection)) return;
        throw new ForbiddenException("permission required: " + ai.core.server.rbac.PermissionCodes.PROJECT_VIEW);
    }

    public List<Project> list(String userId, int offset, int limit, Boolean archivedOnly) {
        var query = new Query();
        query.filter = listFilter(userId, archivedOnly);
        query.sort = Sorts.descending("created_at");
        query.skip = offset;
        query.limit = limit;
        return projectCollection.find(query);
    }

    public long count(String userId, Boolean archivedOnly) {
        return projectCollection.count(listFilter(userId, archivedOnly));
    }

    public void update(String id, String userId, boolean admin, UpdateFields fields) {
        var project = require(id);
        requireAccess(project, userId, admin);
        var updates = new ArrayList<Bson>();
        if (fields.name() != null && !fields.name().isBlank()) updates.add(Updates.set("name", fields.name().trim()));
        updates.add(Updates.set("description", blankToNull(fields.description())));
        updates.add(Updates.set("goal", blankToNull(fields.goal())));
        updates.add(Updates.set("playbook", blankToNull(fields.playbook())));
        if (fields.reportSources() != null) {
            updates.add(Updates.set("report_sources", resolveReportSources(project.userId, fields.reportSources())));
        }
        if (fields.status() != null) {
            if (!STATUS_ACTIVE.equals(fields.status()) && !STATUS_ARCHIVED.equals(fields.status())) throw new BadRequestException("invalid status: " + fields.status());
            updates.add(Updates.set("status", fields.status()));
        }
        updates.add(Updates.set("updated_at", ZonedDateTime.now()));
        projectCollection.update(Filters.eq("_id", id), Updates.combine(updates));
    }

    // report sources must be addable members (own or shared); names are snapshotted so display survives removal
    private List<ProjectReportSource> resolveReportSources(String ownerUserId, List<ReportSourceRef> refs) {
        if (refs == null || refs.isEmpty()) return null;
        var sources = new ArrayList<ProjectReportSource>();
        for (var ref : refs) {
            var source = new ProjectReportSource();
            source.type = ref.type();
            source.id = ref.id();
            if ("agent".equals(ref.type())) {
                var agent = agentCollection.get(ref.id()).orElseThrow(() -> new NotFoundException("agent not found, id=" + ref.id()));
                if (!isAddableAgent(agent, ownerUserId)) throw new ForbiddenException("agent is not published and does not belong to the project owner");
                source.name = agent.name;
            } else if ("workflow".equals(ref.type())) {
                var workflow = workflowCollection.get(ref.id()).orElseThrow(() -> new NotFoundException("workflow not found, id=" + ref.id()));
                if (!ownerUserId.equals(workflow.userId) && !WorkflowDefinitionService.isPublicActive(workflow)) throw new ForbiddenException("workflow is not public and does not belong to the project owner");
                source.name = workflow.name;
            } else {
                throw new BadRequestException("invalid report source type: " + ref.type());
            }
            sources.add(source);
        }
        return sources;
    }

    private boolean isAddableAgent(AgentDefinition agent, String ownerUserId) {
        return ownerUserId.equals(agent.userId) || AgentDependencyAccessPolicy.hasUsablePublishedConfig(agent);
    }

    public void archive(String id, String userId, boolean admin) {
        var project = require(id);
        requireAccess(project, userId, admin);
        projectCollection.update(Filters.eq("_id", id), Updates.combine(
            Updates.set("status", STATUS_ARCHIVED),
            Updates.set("archived_at", ZonedDateTime.now()),
            Updates.set("updated_at", ZonedDateTime.now())));
    }

    public void activate(String id, String userId, boolean admin) {
        var project = require(id);
        requireAccess(project, userId, admin);
        projectCollection.update(Filters.eq("_id", id), Updates.combine(
            Updates.set("status", STATUS_ACTIVE),
            Updates.unset("archived_at"),
            Updates.set("updated_at", ZonedDateTime.now())));
    }

    // ---- subjects: the state carriers of the project (state writes live in ProjectStateService) ----

    public List<ProjectSubject> subjects(String projectId) {
        var query = new Query();
        query.filter = Filters.eq("project_id", projectId);
        query.sort = Sorts.ascending("created_at");
        return subjectCollection.find(query);
    }

    public ProjectSubject subject(String projectId, String subjectId) {
        var entity = subjectCollection.get(subjectId).orElse(null);
        if (entity == null || !projectId.equals(entity.projectId)) {
            throw new BadRequestException("subject does not belong to the project, subjectId=" + subjectId);
        }
        return entity;
    }

    public List<SubjectRef> subjectRefs(String projectId) {
        return subjects(projectId).stream().map(s -> new SubjectRef(s.id, s.name)).toList();
    }

    public ProjectSubject createSubject(String projectId, String userId, boolean admin, String name, String description, String externalLink) {
        var project = require(projectId);
        requireAccess(project, userId, admin);
        var subject = new ProjectSubject();
        subject.id = UUID.randomUUID().toString();
        subject.projectId = projectId;
        subject.userId = project.userId;
        subject.name = name.trim();
        subject.description = blankToNull(description);
        subject.externalLink = blankToNull(externalLink);
        subject.createdAt = ZonedDateTime.now();
        subject.updatedAt = subject.createdAt;
        subjectCollection.insert(subject);
        return subject;
    }

    public void updateSubject(String projectId, String userId, boolean admin, String subjectId, SubjectFields fields) {
        var project = require(projectId);
        requireAccess(project, userId, admin);
        var updates = new ArrayList<Bson>();
        if (fields.name() != null && !fields.name().isBlank()) updates.add(Updates.set("name", fields.name().trim()));
        updates.add(Updates.set("description", blankToNull(fields.description())));
        updates.add(Updates.set("external_link", blankToNull(fields.externalLink())));
        updates.add(Updates.set("updated_at", ZonedDateTime.now()));
        subjectCollection.update(Filters.eq("_id", subjectId), Updates.combine(updates));
        if (fields.status() != null) {
            if (!"started".equals(fields.status()) && !"paused".equals(fields.status())) throw new BadRequestException("invalid subject status: " + fields.status());
            stateService.recordSubjectStatus(projectId, subjectId, fields.status(), userId);
        }
    }

    public void deleteSubject(String projectId, String userId, boolean admin, String subjectId) {
        var project = require(projectId);
        requireAccess(project, userId, admin);
        subject(projectId, subjectId);
        if (attributionCollection.count(Filters.eq("subject_id", subjectId)) > 0) throw new BadRequestException("subject is referenced by attributions and cannot be deleted; rename it instead");
        subjectCollection.delete(Filters.eq("_id", subjectId));
        stateService.deleteSubjectEvents(subjectId);
    }

    // ---- membership: lives on the PROJECT side (embedded members list) — agent/workflow definitions
    // carry no project link. Shared members are allowed: published agents, public active workflows.

    public void addMember(String projectId, String userId, boolean admin, String type, String memberId) {
        var project = require(projectId);
        requireAccess(project, userId, admin);
        if ("agent".equals(type)) {
            var agent = agentCollection.get(memberId).orElseThrow(() -> new NotFoundException("agent not found, id=" + memberId));
            if (!isAddableAgent(agent, project.userId)) throw new ForbiddenException("agent is not published and does not belong to the project owner");
            pushMember(project, type, memberId, agent.name);
        } else if ("workflow".equals(type)) {
            var workflow = workflowCollection.get(memberId).orElseThrow(() -> new NotFoundException("workflow not found, id=" + memberId));
            if (!project.userId.equals(workflow.userId) && !WorkflowDefinitionService.isPublicActive(workflow)) throw new ForbiddenException("workflow is not public and does not belong to the project owner");
            pushMember(project, type, memberId, workflow.name);
        } else {
            throw new BadRequestException("invalid member type: " + type);
        }
    }

    public void removeMember(String projectId, String userId, boolean admin, String type, String memberId) {
        var project = require(projectId);
        requireAccess(project, userId, admin);
        if (!"agent".equals(type) && !"workflow".equals(type)) throw new BadRequestException("invalid member type: " + type);
        var members = new ArrayList<ProjectMemberRef>();
        if (project.members != null) members.addAll(project.members);
        project.members = members.stream().filter(m -> !(type.equals(m.type) && memberId.equals(m.id))).toList();
        if (project.members.isEmpty()) project.members = null;
        project.statsDirty = Boolean.TRUE;
        project.updatedAt = ZonedDateTime.now();
        projectCollection.replace(project);
    }

    private void pushMember(Project project, String type, String memberId, String name) {
        var members = new ArrayList<ProjectMemberRef>();
        if (project.members != null) members.addAll(project.members);
        if (members.stream().anyMatch(m -> type.equals(m.type) && memberId.equals(m.id))) return;
        var member = new ProjectMemberRef();
        member.type = type;
        member.id = memberId;
        member.name = name;
        members.add(member);
        project.members = members;
        project.statsDirty = Boolean.TRUE;
        project.updatedAt = ZonedDateTime.now();
        projectCollection.replace(project);
    }

    // ---- subject attribution (analysis output): rows in a side table, raw records stay untouched ----

    public void attribute(String projectId, String subjectId, String targetType, String targetId) {
        validateWrite(projectId, subjectId);
        if (targetType == null || targetId == null || targetId.isBlank()) throw new BadRequestException("target_type and target_id are required");
        if (!List.of("session", "run", "workflow_run", "file").contains(targetType)) throw new BadRequestException("invalid attribution target type: " + targetType);
        if (attributionCollection.count(Filters.and(
                Filters.eq("subject_id", subjectId),
                Filters.eq("target_type", targetType),
                Filters.eq("target_id", targetId))) > 0) return;
        var attribution = new ProjectSubjectAttribution();
        attribution.id = UUID.randomUUID().toString();
        attribution.subjectId = subjectId;
        attribution.targetType = targetType;
        attribution.targetId = targetId;
        attribution.createdAt = ZonedDateTime.now();
        try {
            attributionCollection.insert(attribution);
        } catch (MongoWriteException e) {
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
            // concurrent duplicate: the existing row is equivalent, treat as no-op
        }
    }

    private Bson listFilter(String userId, Boolean archivedOnly) {
        var filters = new ArrayList<Bson>();
        if (!ProjectAccess.canViewAll(userId, permissionService, userCollection)) {
            filters.add(Filters.eq("user_id", userId));
        }
        if (Boolean.TRUE.equals(archivedOnly)) {
            filters.add(Filters.eq("status", STATUS_ARCHIVED));
        } else if (Boolean.FALSE.equals(archivedOnly)) {
            filters.add(Filters.eq("status", STATUS_ACTIVE));
        }
        return filters.isEmpty() ? new org.bson.Document() : Filters.and(filters);
    }

    // every write requires a subject: the project itself holds no state (it is a scaffold)
    private void validateWrite(String projectId, String subjectId) {
        require(projectId);
        if (subjectId == null || subjectId.isBlank()) throw new BadRequestException("subject_id is required: the project itself holds no state, state belongs to subjects");
        subject(projectId, subjectId);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public record SubjectRef(String id, String name) {
    }

    public record UpdateFields(String name, String description, String goal, String playbook,
                               List<ReportSourceRef> reportSources, String status) {
    }

    public record ReportSourceRef(String type, String id) {
    }

    public record SubjectFields(String name, String description, String externalLink, String status) {
    }
}
