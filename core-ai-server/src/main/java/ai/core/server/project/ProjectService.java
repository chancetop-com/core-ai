package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectMemberRef;
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
import org.bson.Document;
import org.bson.conversions.Bson;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Project (campaign container) domain service: CRUD, subject and membership management and the
 * attribution table. Subject state writes (status/KPIs/action items/notes + history events) live
 * in {@link ProjectStateService}; read-side aggregations live in {@link ProjectQueryService}.
 * Project writes are targeted {@code $set}s: the analysis jobs update cursors/claims on the same
 * document concurrently, so a whole-document replace would clobber them.
 *
 * @author stephen
 */
public class ProjectService {
    static final int DEFAULT_LIMIT = 50;
    static final int MAX_PAGE_LIMIT = 200;
    static final String STATUS_ACTIVE = "active";
    static final String STATUS_ARCHIVED = "archived";
    static final String ANALYSIS_RUNNING = "running";

    // subject provenance: manual (null = manual) rows are human-created, auto rows come from the
    // attribution stage's proposal pass
    static final String SOURCE_MANUAL = "manual";
    static final String SOURCE_AUTO = "auto";
    // subject tracking status: proposed = an auto-discovered candidate waiting for review
    static final String SUBJECT_PROPOSED = "proposed";
    static final String SUBJECT_STARTED = "started";
    // subject auto-discovery mode: off = never propose, propose = create as proposed, create = create started
    static final String AUTO_SUBJECTS_PROPOSE = "propose";
    static final String AUTO_SUBJECTS_CREATE = "create";
    static final String AUTO_SUBJECTS_OFF = "off";
    static final int DUPLICATE_KEY_CODE = 11000;

    // null = propose: a missing value must behave like the default (no backfill migration)
    public static String autoSubjectMode(Project project) {
        if (project == null || project.autoSubjects == null || project.autoSubjects.isBlank()) return AUTO_SUBJECTS_PROPOSE;
        return project.autoSubjects;
    }

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
    @Inject
    ProjectAttributionStore attributionStore;
    @Inject
    ProjectArtifactBinder artifactBinder;

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
            // raw Documents: core-ng only generates codecs for @Collection classes, $set of the
            // embedded ProjectReportSource list fails with "Can't find a codec"
            updates.add(Updates.set("report_sources", resolveReportSources(project.userId, fields.reportSources())));
        }
        if (fields.status() != null) {
            if (!STATUS_ACTIVE.equals(fields.status()) && !STATUS_ARCHIVED.equals(fields.status())) throw new BadRequestException("invalid status: " + fields.status());
            updates.add(Updates.set("status", fields.status()));
        }
        updates.add(Updates.set("updated_at", ZonedDateTime.now()));
        projectCollection.update(Filters.eq("_id", id), Updates.combine(updates));
    }

    public void updateAutoSubjects(String projectId, String userId, boolean admin, String mode) {
        var project = require(projectId);
        requireAccess(project, userId, admin);
        if (!AUTO_SUBJECTS_OFF.equals(mode) && !AUTO_SUBJECTS_PROPOSE.equals(mode) && !AUTO_SUBJECTS_CREATE.equals(mode)) {
            throw new BadRequestException("invalid auto_subjects mode: " + mode);
        }
        projectCollection.update(Filters.eq("_id", projectId), Updates.combine(
            Updates.set("auto_subjects", mode),
            Updates.set("updated_at", ZonedDateTime.now())));
    }

    // report sources must be addable members (own or shared); names are snapshotted so display survives removal
    private List<Document> resolveReportSources(String ownerUserId, List<ReportSourceRef> refs) {
        var sources = new ArrayList<Document>();
        for (var ref : refs) {
            String name;
            if ("agent".equals(ref.type())) {
                var agent = agentCollection.get(ref.id()).orElseThrow(() -> new NotFoundException("agent not found, id=" + ref.id()));
                if (!isAddableAgent(agent, ownerUserId)) throw new ForbiddenException("agent is not published and does not belong to the project owner");
                name = agent.name;
            } else if ("workflow".equals(ref.type())) {
                var workflow = workflowCollection.get(ref.id()).orElseThrow(() -> new NotFoundException("workflow not found, id=" + ref.id()));
                if (!ownerUserId.equals(workflow.userId) && !WorkflowDefinitionService.isPublicActive(workflow)) throw new ForbiddenException("workflow is not public and does not belong to the project owner");
                name = workflow.name;
            } else {
                throw new BadRequestException("invalid report source type: " + ref.type());
            }
            sources.add(refDocument(ref.type(), ref.id(), name));
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

    /**
     * Internal write of the attribution stage (no access check — the pipeline runs as the system):
     * an auto-discovered subject, either a proposal awaiting review or directly started. The
     * normalized name key backs the partial unique index that keeps proposals from duplicating.
     */
    public ProjectSubject createAutoSubject(String projectId, String name, String description, String reason, String status) {
        var project = require(projectId);
        var subject = new ProjectSubject();
        subject.id = UUID.randomUUID().toString();
        subject.projectId = projectId;
        subject.userId = project.userId;
        subject.name = name.trim();
        subject.description = blankToNull(description);
        subject.source = SOURCE_AUTO;
        subject.nameKey = ProjectSubjectNames.normalize(subject.name);
        subject.proposalReason = blankToNull(reason);
        subject.status = status;
        subject.proposedAt = SUBJECT_PROPOSED.equals(status) ? ZonedDateTime.now() : null;
        subject.createdAt = ZonedDateTime.now();
        subject.updatedAt = subject.createdAt;
        try {
            subjectCollection.insert(subject);
        } catch (MongoWriteException e) {
            // the partial unique index caught a concurrent run proposing the same entity: reuse it
            if (e.getCode() != DUPLICATE_KEY_CODE) throw e;
            var existing = autoSubjectByNameKey(projectId, subject.nameKey);
            if (existing == null) throw e;
            return existing;
        }
        markStatsDirty(projectId);
        return subject;
    }

    private ProjectSubject autoSubjectByNameKey(String projectId, String nameKey) {
        var query = new Query();
        query.filter = Filters.and(Filters.eq("project_id", projectId), Filters.eq("name_key", nameKey), Filters.eq("source", SOURCE_AUTO));
        return subjectCollection.find(query).stream().findFirst().orElse(null);
    }

    public void updateSubject(String projectId, String userId, boolean admin, String subjectId, SubjectFields fields) {
        var project = require(projectId);
        requireAccess(project, userId, admin);
        subject(projectId, subjectId);
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

    // a subject that still owns attributed material is protected (the rows are the users' curation);
    // reset-analysis first, then delete. History events go with the subject; the cost snapshot is
    // marked stale so the by-subject rows disappear on the next refresh.
    public void deleteSubject(String projectId, String userId, boolean admin, String subjectId) {
        var project = require(projectId);
        requireAccess(project, userId, admin);
        subject(projectId, subjectId);
        if (attributionCollection.count(Filters.eq("subject_id", subjectId)) > 0) throw new BadRequestException("subject is referenced by attributions and cannot be deleted; reset its analysis first or rename it instead");
        subjectCollection.delete(Filters.eq("_id", subjectId));
        stateService.deleteSubjectEvents(subjectId);
        markStatsDirty(projectId);
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
        var remaining = members.stream().filter(m -> !(type.equals(m.type) && memberId.equals(m.id))).toList();
        writeMembers(project.id, remaining);
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
        writeMembers(project.id, members);
    }

    private void writeMembers(String projectId, List<ProjectMemberRef> members) {
        var docs = members.stream().map(m -> refDocument(m.type, m.id, m.name)).toList();
        projectCollection.update(Filters.eq("_id", projectId), Updates.combine(
            docs.isEmpty() ? Updates.unset("members") : Updates.set("members", docs),
            Updates.set("stats_dirty", Boolean.TRUE),
            Updates.set("updated_at", ZonedDateTime.now())));
    }

    private Document refDocument(String type, String id, String name) {
        var doc = new Document("type", type).append("id", id);
        if (name != null) doc.append("name", name);
        return doc;
    }

    private void markStatsDirty(String projectId) {
        projectCollection.update(Filters.eq("_id", projectId), Updates.combine(
            Updates.set("stats_dirty", Boolean.TRUE),
            Updates.set("updated_at", ZonedDateTime.now())));
    }

    /**
     * Rewinds the attribution backfill cursor to {@code from} (never forward): the next rounds walk the
     * scanned history again from there. Only a rescan needs it — records the cursor already passed are
     * outside the newest batch and unreachable for good otherwise.
     */
    public void rewindAttributionBackfill(String projectId, ZonedDateTime from) {
        projectCollection.update(Filters.and(Filters.eq("_id", projectId), Filters.gt("attribution_backfilled_at", from)),
            Updates.set("attribution_backfilled_at", from));
    }

    // ---- subject attribution (analysis output): rows in a side table, raw records stay untouched ----

    public void attribute(String projectId, String subjectId, String targetType, String targetId) {
        attribute(projectId, subjectId, targetType, targetId, ProjectAttributionStore.SOURCE_ATTRIBUTOR);
    }

    /**
     * Files have ONE home per project: attributing a file already homed under another subject is rejected
     * (use {@link #moveReport} for an explicit re-home). Attributing a session/run pulls its artifacts along
     * (cascade), so reports never stay orphaned behind an attributed conversation.
     */
    public void attribute(String projectId, String subjectId, String targetType, String targetId, String source) {
        validateWrite(projectId, subjectId);
        if (targetType == null || targetId == null || targetId.isBlank()) throw new BadRequestException("target_type and target_id are required");
        if (!ProjectAttributionStore.TARGET_TYPES.contains(targetType)) throw new BadRequestException("invalid attribution target type: " + targetType);
        var result = attributionStore.attribute(projectId, subjectId, targetType, targetId, source);
        if (result == ProjectAttributionStore.Result.CONFLICT) {
            throw new BadRequestException("file is already attributed to another subject of this project, move it explicitly instead, fileId=" + targetId);
        }
        if (ProjectAttributionStore.TARGET_SESSION.equals(targetType) || ProjectAttributionStore.TARGET_RUN.equals(targetType)) {
            artifactBinder.cascade(projectId, subjectId, targetType, targetId);
        }
    }

    /** manual re-home of a report (null subject = back to the project's unassigned bucket) */
    public void moveReport(String projectId, String userId, boolean admin, String fileId, String subjectId) {
        var project = require(projectId);
        requireAccess(project, userId, admin);
        if (fileId == null || fileId.isBlank()) throw new BadRequestException("file_id is required");
        if (subjectId != null && !subjectId.isBlank()) subject(projectId, subjectId);
        attributionStore.moveFile(projectId, blankToNull(subjectId), fileId, ProjectAttributionStore.SOURCE_MANUAL);
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
        return filters.isEmpty() ? new Document() : Filters.and(filters);
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

    public record UpdateFields(String name, String description, String goal, String playbook,
                               List<ReportSourceRef> reportSources, String status) {
    }

    public record ReportSourceRef(String type, String id) {
    }

    public record SubjectFields(String name, String description, String externalLink, String status) {
    }
}
