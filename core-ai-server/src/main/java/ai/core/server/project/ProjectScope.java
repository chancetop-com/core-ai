package ai.core.server.project;

import ai.core.server.domain.Project;
import core.framework.mongo.MongoCollection;

import java.util.List;

/**
 * Member-derivation scope for one project: the owner plus the member agent/workflow ids that all
 * project material is derived from. Raw records carry no binding fields — the member lists are the
 * single source of attribution for project-level views.
 *
 * @author stephen
 */
final class ProjectScope {
    static ProjectScope resolve(MongoCollection<Project> projectCollection, String projectId) {
        var project = projectCollection.get(projectId).orElse(null);
        if (project == null || project.members == null || project.members.isEmpty()) return null;
        var agentIds = project.members.stream().filter(m -> "agent".equals(m.type)).map(m -> m.id).toList();
        var workflowIds = project.members.stream().filter(m -> "workflow".equals(m.type)).map(m -> m.id).toList();
        return new ProjectScope(projectId, agentIds, workflowIds);
    }

    final String projectId;
    final List<String> agentIds;
    final List<String> workflowIds;

    private ProjectScope(String projectId, List<String> agentIds, List<String> workflowIds) {
        this.projectId = projectId;
        this.agentIds = agentIds;
        this.workflowIds = workflowIds;
    }
}
