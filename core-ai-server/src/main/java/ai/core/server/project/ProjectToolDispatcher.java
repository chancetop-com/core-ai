package ai.core.server.project;

import ai.core.agent.ExecutionContext;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectReportDraft;
import ai.core.server.domain.ProjectSubjectAttribution;
import ai.core.server.domain.User;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.Updates;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;

import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Builtin tool entry point of the {@code project} tool group, mounted on the builtin project-agent:
 * loads the project context (playbook, members, subjects with attribution counts and cursors).
 * The two writer tools are the LLM_CALL definitions themselves (mounted as native tools with
 * apply-on-execute); this dispatcher only serves context.
 *
 * @author stephen
 */
public class ProjectToolDispatcher {
    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubjectAttribution> attributionCollection;
    @Inject
    MongoCollection<ProjectReportDraft> draftCollection;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    PermissionService permissionService;
    @Inject
    ProjectService projectService;

    public Map<String, Object> getProjectInfo(String projectId, ExecutionContext context) {
        var project = requireAccessible(projectId, context);
        var info = new LinkedHashMap<String, Object>();
        info.put("project_id", project.id);
        info.put("name", project.name);
        info.put("goal", project.goal);
        info.put("playbook", project.playbook);
        info.put("report_sources", project.reportSources);
        info.put("members", project.members);
        info.put("last_analyzed_at", project.lastAnalyzedAt != null ? project.lastAnalyzedAt.toString() : null);
        var subjects = projectService.subjects(projectId);
        info.put("subjects", subjects.stream().map(s -> {
            var subject = new LinkedHashMap<String, Object>();
            subject.put("id", s.id);
            subject.put("name", s.name);
            subject.put("description", s.description);
            subject.put("status", s.status != null ? s.status : "not_started");
            subject.put("analyzed_at", s.analyzedAt != null ? s.analyzedAt.toString() : null);
            subject.put("attributed_targets", attributionCounts(s.id));
            return subject;
        }).toList());
        return info;
    }

    private Project requireAccessible(String projectId, ExecutionContext context) {
        var project = projectCollection.get(projectId)
            .orElseThrow(() -> new ForbiddenException("project not found, id=" + projectId));
        var caller = context != null ? context.getCaller() : null;
        var callerUserId = caller != null ? caller.userId() : null;
        if (!ProjectAccess.canView(project, callerUserId, permissionService, userCollection)) {
            throw new ForbiddenException("project is not accessible to the current user");
        }
        return project;
    }

    private Map<String, Long> attributionCounts(String subjectId) {
        var counts = new LinkedHashMap<String, Long>();
        counts.put("session", attributionCollection.count(Filters.and(
            Filters.eq("subject_id", subjectId),
            Filters.eq("target_type", "session"))));
        counts.put("run", attributionCollection.count(Filters.and(
            Filters.eq("subject_id", subjectId),
            Filters.eq("target_type", "run"))));
        counts.put("workflow_run", attributionCollection.count(Filters.and(
            Filters.eq("subject_id", subjectId),
            Filters.eq("target_type", "workflow_run"))));
        counts.put("file", attributionCollection.count(Filters.and(
            Filters.eq("subject_id", subjectId),
            Filters.eq("target_type", "file"))));
        return counts;
    }

    // report renderer tool: appends one HTML section to the run's draft (draft_id is auto-injected
    // from the run's runtime variables); the report stage assembles the sections when the agent
    // run finishes
    public Map<String, Object> appendReportSection(String sectionHtml, ExecutionContext context) {
        if (sectionHtml == null || sectionHtml.isBlank()) {
            throw new core.framework.web.exception.BadRequestException("section_html is required");
        }
        var draftId = runtimeVariable(context, "draft_id");
        if (draftId == null) throw new ForbiddenException("report draft is not available in this run");
        if (draftCollection.get(draftId).isEmpty()) {
            throw new ForbiddenException("report draft not found, id=" + draftId);
        }
        var html = sectionHtml.trim();
        if (html.length() > 8000) html = html.substring(0, 8000);
        draftCollection.update(Filters.eq("_id", draftId), Updates.combine(
            Updates.push("sections", html),
            Updates.set("updated_at", ZonedDateTime.now())));
        return Map.of("appended", Boolean.TRUE);
    }

    private String runtimeVariable(ExecutionContext context, String name) {
        if (context == null || context.getCustomVariables() == null) return null;
        var value = context.getCustomVariables().get(name);
        return value != null ? value.toString() : null;
    }
}
