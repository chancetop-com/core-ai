package ai.core.server.project;

import ai.core.agent.ExecutionContext;
import ai.core.server.apiuser.PermissionService;
import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.Project;
import ai.core.server.domain.ProjectSubject;
import ai.core.server.domain.User;
import ai.core.server.run.LLMCallExecutor;
import ai.core.tool.ToolCall;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.web.exception.ForbiddenException;

/**
 * Creates the apply-on-execute writer tools for the builtin project LLM_CALL definitions and
 * resolves the run's project/subject scope (permission-checked) at tool execution time.
 *
 * @author stephen
 */
public class ProjectWriterToolFactory implements ProjectWriterTool.ProjectScopeLoader {
    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<ProjectSubject> subjectCollection;
    @Inject
    MongoCollection<User> userCollection;
    @Inject
    PermissionService permissionService;
    @Inject
    ProjectAttributionStage attributionStage;
    @Inject
    ProjectSubjectAnalysisStage subjectAnalysisStage;

    ToolCall create(String definitionId, AgentDefinition definition, LLMCallExecutor executor) {
        return ProjectWriterTool.create(definitionId, definition, executor, attributionStage, subjectAnalysisStage, this);
    }

    @Override
    public Scope requireOwnedScope(ExecutionContext context) {
        var projectId = projectId(context);
        var project = projectCollection.get(projectId)
            .orElseThrow(() -> new ForbiddenException("project not found, id=" + projectId));
        var caller = context != null ? context.getCaller() : null;
        var callerUserId = caller != null ? caller.userId() : null;
        if (!ProjectAccess.canManage(project, callerUserId, permissionService, userCollection)) {
            throw new ForbiddenException("project is not manageable by the current user");
        }
        return new Scope(projectId, project);
    }

    @Override
    public ProjectSubject requireSubject(String projectId, String subjectId) {
        var subject = subjectCollection.get(subjectId).orElse(null);
        if (subject == null || !projectId.equals(subject.projectId)) return null;
        return subject;
    }

    private String projectId(ExecutionContext context) {
        if (context == null || context.getCustomVariables() == null) return null;
        var value = context.getCustomVariables().get("project_id");
        return value != null ? value.toString() : null;
    }
}
