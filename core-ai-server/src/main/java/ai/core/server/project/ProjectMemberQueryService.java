package ai.core.server.project;

import ai.core.server.domain.AgentDefinition;
import ai.core.server.domain.AgentStatus;
import ai.core.server.domain.Project;
import ai.core.server.domain.WorkflowDefinition;
import ai.core.server.domain.WorkflowDefinitionStatus;
import ai.core.server.domain.WorkflowVisibility;
import com.mongodb.client.model.Filters;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;
import core.framework.mongo.Query;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * Membership queries of the project feature: the project's own member list (embedded) plus the
 * addable options (the owner's agents/workflows PLUS shared ones — published agents with a usable
 * config, public active workflows), matching the rules {@code ProjectService.addMember} accepts.
 *
 * @author stephen
 */
public class ProjectMemberQueryService {
    @Inject
    MongoCollection<Project> projectCollection;
    @Inject
    MongoCollection<AgentDefinition> agentCollection;
    @Inject
    MongoCollection<WorkflowDefinition> workflowCollection;

    public List<ProjectQueryService.ProjectMember> members(String projectId) {
        var project = projectCollection.get(projectId).orElse(null);
        if (project == null || project.members == null) return List.of();
        return project.members.stream().map(m -> new ProjectQueryService.ProjectMember(m.id, m.name, m.type))
            .sorted(java.util.Comparator.comparing(m -> m.name() == null ? "" : m.name())).toList();
    }

    // Two single-field indexed queries per type, merged in memory (an OR filter would trip notablescan).
    public ProjectQueryService.MemberOptions memberOptions(String projectId) {
        var project = projectCollection.get(projectId).orElse(null);
        if (project == null) return new ProjectQueryService.MemberOptions(List.of(), List.of());
        return new ProjectQueryService.MemberOptions(availableAgents(project.userId), availableWorkflows(project.userId));
    }

    private List<ProjectQueryService.ProjectMember> availableAgents(String ownerUserId) {
        var byId = new LinkedHashMap<String, ProjectQueryService.ProjectMember>();
        var ownQuery = new Query();
        ownQuery.filter = Filters.eq("user_id", ownerUserId);
        for (var agent : agentCollection.find(ownQuery)) {
            byId.putIfAbsent(agent.id, new ProjectQueryService.ProjectMember(agent.id, agent.name, "agent"));
        }
        var sharedQuery = new Query();
        sharedQuery.filter = Filters.eq("status", AgentStatus.PUBLISHED);
        for (var agent : agentCollection.find(sharedQuery)) {
            byId.putIfAbsent(agent.id, new ProjectQueryService.ProjectMember(agent.id, agent.name, "agent"));
        }
        var result = new ArrayList<>(byId.values());
        result.sort(java.util.Comparator.comparing(m -> m.name() == null ? "" : m.name()));
        return result;
    }

    private List<ProjectQueryService.ProjectMember> availableWorkflows(String ownerUserId) {
        var byId = new LinkedHashMap<String, ProjectQueryService.ProjectMember>();
        var ownQuery = new Query();
        ownQuery.filter = Filters.eq("user_id", ownerUserId);
        for (var workflow : workflowCollection.find(ownQuery)) {
            byId.putIfAbsent(workflow.id, new ProjectQueryService.ProjectMember(workflow.id, workflow.name, "workflow"));
        }
        var sharedQuery = new Query();
        sharedQuery.filter = Filters.and(
            Filters.eq("visibility", WorkflowVisibility.PUBLIC),
            Filters.eq("status", WorkflowDefinitionStatus.ACTIVE));
        for (var workflow : workflowCollection.find(sharedQuery)) {
            if (workflow.publishedVersionId != null) {
                byId.putIfAbsent(workflow.id, new ProjectQueryService.ProjectMember(workflow.id, workflow.name, "workflow"));
            }
        }
        var result = new ArrayList<>(byId.values());
        result.sort(java.util.Comparator.comparing(m -> m.name() == null ? "" : m.name()));
        return result;
    }
}
