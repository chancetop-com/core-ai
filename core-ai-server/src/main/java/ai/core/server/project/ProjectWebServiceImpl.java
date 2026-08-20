package ai.core.server.project;

import ai.core.api.server.project.AddProjectMemberRequest;
import ai.core.api.server.project.AnalyzeProjectResponse;
import ai.core.api.server.project.CreateProjectRequest;
import ai.core.api.server.project.CreateProjectResponse;
import ai.core.api.server.project.CreateSubjectRequest;
import ai.core.api.server.project.CreateSubjectResponse;
import ai.core.api.server.project.GetProjectRequest;
import ai.core.api.server.project.GetProjectStatsRequest;
import ai.core.api.server.project.ListProjectEventsRequest;
import ai.core.api.server.project.ListProjectEventsResponse;
import ai.core.api.server.project.ListProjectExecutionsRequest;
import ai.core.api.server.project.ListProjectExecutionsResponse;
import ai.core.api.server.project.ListProjectMembersResponse;
import ai.core.api.server.project.ListProjectReportsRequest;
import ai.core.api.server.project.ListProjectReportsResponse;
import ai.core.api.server.project.ListProjectSubjectsRequest;
import ai.core.api.server.project.ListProjectSubjectsResponse;
import ai.core.api.server.project.ListProjectsRequest;
import ai.core.api.server.project.ListProjectsResponse;
import ai.core.api.server.project.ListTimelineRequest;
import ai.core.api.server.project.ListTimelineResponse;
import ai.core.api.server.project.ProjectMemberView;
import ai.core.api.server.project.ProjectStatsView;
import ai.core.api.server.project.ProjectSummaryView;
import ai.core.api.server.project.ProjectView;
import ai.core.api.server.project.ProjectWebService;
import ai.core.api.server.project.UpdateProjectRequest;
import ai.core.api.server.project.UpdateSubjectRequest;
import ai.core.server.domain.Project;
import ai.core.server.domain.User;
import ai.core.server.rbac.PermissionCodes;
import ai.core.server.rbac.PermissionsRequired;
import ai.core.server.web.auth.AuthContext;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.WebContext;
import core.framework.web.exception.BadRequestException;
import core.framework.web.exception.ForbiddenException;
import core.framework.web.exception.UnauthorizedException;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * @author stephen
 */
@PermissionsRequired(PermissionCodes.PROJECT_VIEW)
public class ProjectWebServiceImpl implements ProjectWebService {
    @Inject
    WebContext webContext;
    @Inject
    ProjectService projectService;
    @Inject
    ProjectQueryService queryService;
    @Inject
    ProjectMemberQueryService memberQueryService;
    @Inject
    ProjectStatsQueryService statsQueryService;
    @Inject
    ProjectAnalysisService analysisService;
    @Inject
    ProjectResetService resetService;
    @Inject
    ProjectViewAssembler assembler;
    @Inject
    MongoCollection<User> userCollection;

    @Override
    public ListProjectsResponse list(ListProjectsRequest request) {
        var userId = userId();
        int offset = request.offset == null ? 0 : request.offset;
        int limit = clampLimit(request.limit);
        var projects = projectService.list(userId, offset, limit, request.archived);
        var response = new ListProjectsResponse();
        response.projects = projects.stream().map(this::toSummary).toList();
        response.total = projectService.count(userId, request.archived);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public CreateProjectResponse create(CreateProjectRequest request) {
        var project = projectService.create(userId(), request.name, request.description, request.goal);
        var response = new CreateProjectResponse();
        response.id = project.id;
        return response;
    }

    @Override
    public ProjectView get(String id, GetProjectRequest request) {
        var project = requireAccessible(id);
        var view = new ProjectView();
        view.id = project.id;
        view.name = project.name;
        view.description = project.description;
        view.goal = project.goal;
        view.playbook = project.playbook;
        view.reportSources = streamOf(project.reportSources).map(assembler::toReportSourceView).toList();
        view.status = project.status;
        view.lastAnalyzedAt = project.lastAnalyzedAt;
        view.attributionBackfilledAt = project.attributionBackfilledAt;
        view.lastAnalysisAt = project.lastAnalysisAt;
        view.analysisStatus = project.analysisStatus;
        view.analysisError = project.analysisError;
        view.createdAt = project.createdAt;
        view.updatedAt = project.updatedAt;
        view.archivedAt = project.archivedAt;
        view.subjects = projectService.subjects(project.id).stream().map(assembler::toSubjectView).toList();
        var subjectId = request.subjectId;
        view.subjectStatuses = streamOf(project.subjectStatuses).filter(s -> matches(s.subjectId, subjectId)).map(assembler::toSubjectStatusView).toList();
        view.kpis = streamOf(project.kpis).filter(k -> matches(k.subjectId, subjectId)).map(assembler::toKpiView).toList();
        view.actionItems = streamOf(project.actionItems).filter(i -> matches(i.subjectId, subjectId)).map(assembler::toActionItemView).toList();
        view.notes = streamOf(project.notes).filter(n -> matches(n.subjectId, subjectId)).map(assembler::toNoteView).toList();
        return view;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public void update(String id, UpdateProjectRequest request) {
        var sources = request.reportSources == null ? null : request.reportSources.stream()
            .map(s -> new ProjectService.ReportSourceRef(s.type, s.id))
            .toList();
        var fields = new ProjectService.UpdateFields(request.name, request.description, request.goal, request.playbook, sources, request.status);
        projectService.update(id, userId(), admin(), fields);
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public void archive(String id) {
        projectService.archive(id, userId(), admin());
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public void activate(String id) {
        projectService.activate(id, userId(), admin());
    }

    @Override
    public ListProjectExecutionsResponse executions(String id, ListProjectExecutionsRequest request) {
        requireAccessible(id);
        int offset = request.offset == null ? 0 : request.offset;
        int limit = clampLimit(request.limit);
        var rows = queryService.executions(id, request.type, request.subjectId, offset, limit);
        var response = new ListProjectExecutionsResponse();
        response.executions = rows.stream().map(assembler::toExecutionView).toList();
        response.total = queryService.executionCount(id, request.type, request.subjectId);
        return response;
    }

    @Override
    public ListProjectReportsResponse reports(String id, ListProjectReportsRequest request) {
        requireAccessible(id);
        var response = new ListProjectReportsResponse();
        response.reports = queryService.reports(id, request.subjectId, request.agentId).stream().map(assembler::toReportView).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public AnalyzeProjectResponse analyze(String id, ai.core.api.server.project.AnalyzeProjectRequest request) {
        requireAccessible(id);
        if (!analysisService.claimAnalysis(id)) {
            var project = projectService.require(id);
            var claimedAt = project.analysisClaimedAt != null
                ? project.analysisClaimedAt.toLocalDateTime().withNano(0).toString() : "unknown";
            throw new BadRequestException("an analysis is already running for this project (claimed at " + claimedAt
                + "); it will finish on its own — wait a moment and try again");
        }
        var result = analysisService.runManualAnalysis(id, request.subjectId);
        var view = new AnalyzeProjectResponse();
        view.attributed = result.attributed();
        view.analyzed = result.analyzed();
        view.updated = result.updated();
        return view;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public void report(String id, String subjectId) {
        requireAccessible(id);
        projectService.subject(id, subjectId);   // validates the subject belongs to the project
        try {
            analysisService.regenerateReport(id, subjectId);
        } catch (RuntimeException e) {
            throw new BadRequestException("report render failed: " + e.getMessage(), "REPORT_RENDER_FAILED", e);
        }
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public void resetSubjectAnalysis(String id, String subjectId) {
        requireAccessible(id);
        projectService.subject(id, subjectId);   // validates the subject belongs to the project
        resetService.reset(id, subjectId);
    }

    @Override
    public ListProjectEventsResponse events(String id, ListProjectEventsRequest request) {
        requireAccessible(id);
        var response = new ListProjectEventsResponse();
        response.events = queryService.events(id, request.subjectId, request.type, request.from, request.to)
            .stream().map(assembler::toEventView).toList();
        return response;
    }

    @Override
    public void resetBuiltinAgents() {
        if (!admin()) {
            throw new ForbiddenException("only admins can reset builtin agents");
        }
        analysisService.resetBuiltinAgents();
    }

    @Override
    public ListProjectSubjectsResponse subjects(String id, ListProjectSubjectsRequest request) {
        requireAccessible(id);
        int offset = request.offset == null ? 0 : request.offset;
        int limit = clampLimit(request.limit);
        var response = new ListProjectSubjectsResponse();
        response.subjects = queryService.subjects(id, offset, limit, request.query).stream().map(assembler::toSubjectView).toList();
        response.total = queryService.subjectCount(id, request.query);
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public CreateSubjectResponse createSubject(String id, CreateSubjectRequest request) {
        var subject = projectService.createSubject(id, userId(), admin(), request.name, request.description, request.externalLink);
        var response = new CreateSubjectResponse();
        response.id = subject.id;
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public void updateSubject(String id, String subjectId, UpdateSubjectRequest request) {
        projectService.updateSubject(id, userId(), admin(), subjectId, new ProjectService.SubjectFields(request.name, request.description, request.externalLink, request.status));
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public void deleteSubject(String id, String subjectId) {
        projectService.deleteSubject(id, userId(), admin(), subjectId);
    }

    @Override
    public ProjectStatsView stats(String id, GetProjectStatsRequest request) {
        requireAccessible(id);
        var stats = statsQueryService.stats(id, request.subjectId);
        var view = new ProjectStatsView();
        view.computedAt = statsQueryService.computedAt(id);
        if (!stats.totals().isEmpty()) {
            var totals = stats.totals().getFirst();
            view.traceCount = totals.count();
            view.totalTokens = totals.tokens();
            view.totalCostUsd = totals.costUsd();
        }
        view.byAgent = stats.byAgent().stream().map(assembler::toAgentStatView).toList();
        view.bySubject = stats.bySubject().stream().map(assembler::toSubjectStatView).toList();
        return view;
    }

    @Override
    public ListProjectMembersResponse members(String id) {
        requireAccessible(id);
        var response = new ListProjectMembersResponse();
        response.agents = new ArrayList<>();
        response.workflows = new ArrayList<>();
        for (var member : memberQueryService.members(id)) {
            var view = new ProjectMemberView();
            view.id = member.id();
            view.name = member.name();
            if ("workflow".equals(member.type())) {
                response.workflows.add(view);
            } else {
                response.agents.add(view);
            }
        }
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public void addMember(String id, AddProjectMemberRequest request) {
        projectService.addMember(id, userId(), admin(), request.type, request.id);
    }

    @Override
    public ListProjectMembersResponse memberOptions(String id) {
        requireAccessible(id);
        var options = memberQueryService.memberOptions(id);
        var response = new ListProjectMembersResponse();
        response.agents = options.agents().stream().map(this::toMemberView).toList();
        response.workflows = options.workflows().stream().map(this::toMemberView).toList();
        return response;
    }

    @Override
    @PermissionsRequired(PermissionCodes.PROJECT_MANAGE)
    public void removeMember(String id, String type, String memberId) {
        projectService.removeMember(id, userId(), admin(), type, memberId);
    }

    @Override
    public ListTimelineResponse timeline(String id, ListTimelineRequest request) {
        requireAccessible(id);
        var response = new ListTimelineResponse();
        response.entries = queryService.timeline(id, request.subjectId).stream().map(assembler::toTimelineView).toList();
        return response;
    }

    private ProjectMemberView toMemberView(ProjectQueryService.ProjectMember member) {
        var view = new ProjectMemberView();
        view.id = member.id();
        view.name = member.name();
        return view;
    }

    private ProjectSummaryView toSummary(Project project) {
        var view = new ProjectSummaryView();
        view.id = project.id;
        view.name = project.name;
        view.description = project.description;
        view.goal = project.goal;
        view.status = project.status;
        view.createdAt = project.createdAt;
        view.updatedAt = project.updatedAt;
        view.archivedAt = project.archivedAt;
        return view;
    }

    private Project requireAccessible(String id) {
        var project = projectService.require(id);
        projectService.requireView(project, userId());
        return project;
    }

    private String userId() {
        var userId = AuthContext.userId(webContext);
        if (userId == null) throw new UnauthorizedException("unauthorized");
        return userId;
    }

    private boolean admin() {
        return userCollection.get(userId()).map(user -> "admin".equals(user.role)).orElse(Boolean.FALSE);
    }

    private int clampLimit(Integer limit) {
        if (limit == null) return ProjectService.DEFAULT_LIMIT;
        return Math.min(Math.max(limit, 1), ProjectService.MAX_PAGE_LIMIT);
    }

    private boolean matches(String recordSubjectId, String filterSubjectId) {
        return filterSubjectId == null || filterSubjectId.equals(recordSubjectId);
    }

    private <T> Stream<T> streamOf(List<T> list) {
        return list != null ? list.stream() : Stream.empty();
    }
}
